package com.example.inventory.application;

import com.example.inventory.api.Requests;
import com.example.inventory.api.Views;
import com.example.inventory.domain.ApiException;
import com.example.inventory.domain.InventoryState;
import com.example.inventory.persistence.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InventoryService {
    private final InventoryRepository inventory;

    public InventoryService(InventoryRepository inventory) {
        this.inventory = inventory;
    }

    @Transactional(readOnly = true)
    public Views.Inventory get(String productId) {
        return inventory.find(productId).map(Views.Inventory::from)
                .orElseThrow(() -> ApiException.notFound("商品库存不存在"));
    }

    @Transactional
    public Views.Inventory adjust(String productId, Requests.StockAdjustment request) {
        int delta = request.quantityDelta();
        if (delta == 0) {
            throw ApiException.badRequest("quantityDelta 不能为 0");
        }
        InventoryState current = inventory.lock(productId)
                .orElseThrow(() -> ApiException.notFound("商品库存不存在"));
        long nextOnHand = (long) current.onHandQuantity() + delta;
        if (nextOnHand < current.regularReservedQuantity()) {
            throw ApiException.conflict("调整后实物库存不能低于普通销售已预留数量");
        }
        if (nextOnHand > Integer.MAX_VALUE) {
            throw ApiException.badRequest("调整后的实物库存超出数据库整数范围");
        }
        if (!inventory.adjustPhysical(productId, delta)) {
            throw ApiException.conflict("库存已变化，请重新读取后再调整");
        }
        InventoryState updated = inventory.find(productId)
                .orElseThrow(() -> new IllegalStateException("调整库存后未找到库存记录"));
        inventory.insertMovement(updated, "STOCK_ADJUSTMENT", delta, 0, 0,
                null, request.reason().trim());
        return Views.Inventory.from(updated);
    }

    @Transactional(readOnly = true)
    public Views.Page<Views.Movement> movements(String productId, int page, int size) {
        if (inventory.find(productId).isEmpty()) {
            throw ApiException.notFound("商品库存不存在");
        }
        int offset = PageSupport.offset(page, size);
        List<Views.Movement> movements = inventory.movements(productId, offset, size);
        long total = inventory.countMovements(productId);
        return new Views.Page<>(movements, page, size, total, PageSupport.totalPages(total, size));
    }
}
