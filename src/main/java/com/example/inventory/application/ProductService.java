package com.example.inventory.application;

import com.example.inventory.api.Requests;
import com.example.inventory.api.Views;
import com.example.inventory.domain.ApiException;
import com.example.inventory.domain.InventoryState;
import com.example.inventory.domain.SaleMode;
import com.example.inventory.persistence.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductService {
    private final InventoryRepository inventory;

    public ProductService(InventoryRepository inventory) {
        this.inventory = inventory;
    }

    @Transactional
    public Views.Product create(Requests.CreateProduct request) {
        validateSaleMode(request.saleMode(), request.presaleLimit());
        int initialStock = request.initialStock() == null ? 0 : request.initialStock();
        inventory.insertProduct(request.productId(), request.name().trim(), clean(request.description()),
                request.price(), request.saleMode(), request.presaleLimit(), initialStock);
        InventoryState state = inventory.find(request.productId())
                .orElseThrow(() -> new IllegalStateException("新建商品后未找到库存记录"));
        if (initialStock > 0) {
            inventory.insertMovement(state, "INITIAL_STOCK", initialStock, 0, 0,
                    null, "商品初始实物库存");
        }
        return Views.Product.from(state);
    }

    @Transactional
    public Views.Product update(String productId, Requests.UpdateProduct request) {
        InventoryState current = inventory.lock(productId)
                .orElseThrow(() -> ApiException.notFound("商品不存在"));
        validateSaleMode(request.saleMode(), request.presaleLimit());
        int nextPresaleLimit = request.presaleLimit() == null ? 0 : request.presaleLimit();
        if (current.presaleReservedQuantity() > 0 && request.saleMode() != SaleMode.PRESALE) {
            throw ApiException.conflict("仍有预售订单占用名额，不能切换为普通销售");
        }
        if (request.saleMode() == SaleMode.PRESALE
                && nextPresaleLimit < current.presaleReservedQuantity()) {
            throw ApiException.conflict("预售上限不能低于当前已预留名额");
        }
        inventory.updateProduct(productId, request.name().trim(), clean(request.description()), request.price(),
                request.saleMode(), request.presaleLimit(), request.active());
        return Views.Product.from(inventory.find(productId)
                .orElseThrow(() -> new IllegalStateException("更新商品后未找到库存记录")));
    }

    @Transactional(readOnly = true)
    public Views.Product get(String productId) {
        return inventory.find(productId).map(Views.Product::from)
                .orElseThrow(() -> ApiException.notFound("商品不存在"));
    }

    @Transactional(readOnly = true)
    public Views.Page<Views.Product> search(String query, SaleMode saleMode, Boolean active, int page, int size) {
        int offset = PageSupport.offset(page, size);
        if (query != null && query.length() > 160) {
            throw ApiException.badRequest("q 长度不能超过 160 个字符");
        }
        List<Views.Product> products = inventory.search(query, saleMode, active, offset, size)
                .stream().map(Views.Product::from).toList();
        long total = inventory.count(query, saleMode, active);
        return new Views.Page<>(products, page, size, total, PageSupport.totalPages(total, size));
    }

    @Transactional
    public void deactivate(String productId) {
        InventoryState current = inventory.lock(productId)
                .orElseThrow(() -> ApiException.notFound("商品不存在"));
        if (current.active()) {
            inventory.deactivate(productId);
        }
    }

    private static void validateSaleMode(SaleMode mode, Integer presaleLimit) {
        if (mode == SaleMode.PRESALE && (presaleLimit == null || presaleLimit <= 0)) {
            throw ApiException.badRequest("预售商品必须设置大于 0 的 presaleLimit");
        }
        if (mode == SaleMode.REGULAR && presaleLimit != null) {
            throw ApiException.badRequest("普通销售商品的 presaleLimit 必须为空");
        }
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
