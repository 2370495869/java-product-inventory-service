package com.example.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryState(
        String productId,
        String name,
        String description,
        BigDecimal price,
        SaleMode saleMode,
        Integer presaleLimit,
        boolean active,
        int onHandQuantity,
        int regularReservedQuantity,
        int presaleReservedQuantity,
        Instant createdAt,
        Instant updatedAt,
        Instant inventoryUpdatedAt) {

    public int availableRegularQuantity() {
        return onHandQuantity - regularReservedQuantity;
    }
}
