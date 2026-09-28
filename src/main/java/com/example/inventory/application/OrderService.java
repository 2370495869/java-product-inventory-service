package com.example.inventory.application;

import com.example.inventory.api.Requests;
import com.example.inventory.api.Views;
import com.example.inventory.domain.ApiException;
import com.example.inventory.domain.InventoryState;
import com.example.inventory.domain.SaleMode;
import com.example.inventory.persistence.InventoryRepository;
import com.example.inventory.persistence.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class OrderService {
    private final InventoryRepository inventory;
    private final OrderRepository orders;

    public OrderService(InventoryRepository inventory, OrderRepository orders) {
        this.inventory = inventory;
        this.orders = orders;
    }

    @Transactional
    public Views.OrderResult create(String idempotencyKey, Requests.CreateOrder request) {
        String key = normalizeKey(idempotencyKey);
        TreeMap<String, Integer> requestedItems = normalizeItems(request);
        String requestHash = requestHash(requestedItems);
        UUID orderId = UUID.randomUUID();

        if (!orders.insertIfAbsent(orderId, key, requestHash)) {
            OrderRepository.Header existing = orders.findByKey(key)
                    .orElseThrow(() -> ApiException.conflict("幂等请求正在处理，请使用相同请求重试"));
            if (!existing.requestHash().equals(requestHash)) {
                throw ApiException.conflict("Idempotency-Key 已用于不同的订单请求");
            }
            return new Views.OrderResult(toView(existing), true);
        }

        Map<String, InventoryState> lockedProducts = new TreeMap<>();
        for (String productId : requestedItems.keySet()) {
            InventoryState state = inventory.lock(productId)
                    .orElseThrow(() -> ApiException.notFound("商品不存在：" + productId));
            if (!state.active()) {
                throw ApiException.conflict("商品已停用：" + productId);
            }
            lockedProducts.put(productId, state);
        }

        for (Map.Entry<String, Integer> entry : requestedItems.entrySet()) {
            String productId = entry.getKey();
            int quantity = entry.getValue();
            InventoryState state = lockedProducts.get(productId);
            if (state.saleMode() == SaleMode.REGULAR) {
                if (state.availableRegularQuantity() < quantity) {
                    throw ApiException.conflict("普通库存不足：" + productId);
                }
                if (!inventory.reserveRegular(productId, quantity)) {
                    throw ApiException.conflict("普通库存不足：" + productId);
                }
            } else {
                Integer limit = state.presaleLimit();
                if (limit == null || state.presaleReservedQuantity() + quantity > limit) {
                    throw ApiException.conflict("预售名额不足：" + productId);
                }
                if (!inventory.reservePresale(productId, quantity, limit)) {
                    throw ApiException.conflict("预售名额不足：" + productId);
                }
            }
            orders.insertItem(orderId, productId, state.name(), state.saleMode(), quantity, state.price());
            InventoryState updated = inventory.find(productId)
                    .orElseThrow(() -> new IllegalStateException("预留后未找到库存记录"));
            inventory.insertMovement(updated, "ORDER_RESERVED", 0,
                    state.saleMode() == SaleMode.REGULAR ? quantity : 0,
                    state.saleMode() == SaleMode.PRESALE ? quantity : 0,
                    orderId, "订单库存预留");
        }

        OrderRepository.Header created = orders.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("新建订单后未找到订单记录"));
        return new Views.OrderResult(toView(created), false);
    }

    @Transactional(readOnly = true)
    public Views.Order get(String id) {
        UUID orderId = parseId(id);
        OrderRepository.Header header = orders.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
        return toView(header);
    }

    @Transactional
    public Views.Order cancel(String id) {
        UUID orderId = parseId(id);
        OrderRepository.Header header = orders.lockById(orderId)
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
        if ("CANCELLED".equals(header.status())) {
            return toView(header);
        }
        if (!"RESERVED".equals(header.status())) {
            throw ApiException.conflict("当前订单状态不能取消");
        }

        List<OrderRepository.Line> lines = orders.items(orderId).stream()
                .sorted(Comparator.comparing(OrderRepository.Line::productId))
                .toList();
        for (OrderRepository.Line line : lines) {
            inventory.lock(line.productId())
                    .orElseThrow(() -> new IllegalStateException("订单商品对应的库存记录不存在"));
        }
        for (OrderRepository.Line line : lines) {
            boolean released = line.saleMode() == SaleMode.REGULAR
                    ? inventory.releaseRegular(line.productId(), line.quantity())
                    : inventory.releasePresale(line.productId(), line.quantity());
            if (!released) {
                throw new IllegalStateException("库存预留流水与当前计数不一致");
            }
            InventoryState updated = inventory.find(line.productId())
                    .orElseThrow(() -> new IllegalStateException("释放预留后未找到库存记录"));
            inventory.insertMovement(updated, "ORDER_CANCELLED", 0,
                    line.saleMode() == SaleMode.REGULAR ? -line.quantity() : 0,
                    line.saleMode() == SaleMode.PRESALE ? -line.quantity() : 0,
                    orderId, "取消订单并释放预留");
        }
        orders.updateStatus(orderId, "CANCELLED");
        return toView(orders.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("取消订单后未找到订单记录")));
    }

    private Views.Order toView(OrderRepository.Header header) {
        List<Views.OrderItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (OrderRepository.Line line : orders.items(header.orderId())) {
            BigDecimal lineTotal = line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()));
            total = total.add(lineTotal);
            items.add(new Views.OrderItem(line.productId(), line.productName(), line.saleMode(),
                    line.quantity(), line.unitPrice(), lineTotal));
        }
        return new Views.Order(header.orderId().toString(), header.status(), total,
                List.copyOf(items), header.createdAt(), header.updatedAt());
    }

    private static TreeMap<String, Integer> normalizeItems(Requests.CreateOrder request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw ApiException.badRequest("订单至少需要一个商品");
        }
        if (request.items().size() > 50) {
            throw ApiException.badRequest("单个订单最多包含 50 种商品");
        }
        TreeMap<String, Integer> result = new TreeMap<>();
        for (Requests.OrderLine item : request.items()) {
            if (item == null || item.productId() == null || item.quantity() == null || item.quantity() <= 0) {
                throw ApiException.badRequest("订单商品编号和正数量不能为空");
            }
            if (result.putIfAbsent(item.productId(), item.quantity()) != null) {
                throw ApiException.badRequest("同一商品在订单中只能出现一次：" + item.productId());
            }
        }
        return result;
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank() || key.length() > 200 || !key.equals(key.trim())) {
            throw ApiException.badRequest("Idempotency-Key 必须为 1 到 200 个非空且无首尾空格的字符");
        }
        return key;
    }

    private static String requestHash(TreeMap<String, Integer> items) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, Integer> item : items.entrySet()) {
                digest.update(item.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(item.getValue().toString().getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时缺少 SHA-256", exception);
        }
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("订单编号格式无效");
        }
    }
}
