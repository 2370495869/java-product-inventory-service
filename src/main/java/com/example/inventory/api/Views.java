package com.example.inventory.api;

import com.example.inventory.domain.InventoryState;
import com.example.inventory.domain.SaleMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class Views {
    private Views() {}

    public record Page<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}

    public record Product(
            String productId,
            String name,
            String description,
            BigDecimal price,
            SaleMode saleMode,
            Integer presaleLimit,
            boolean active,
            int onHandQuantity,
            int regularReservedQuantity,
            int availableRegularQuantity,
            int presaleReservedQuantity,
            Instant createdAt,
            Instant updatedAt) {
        public static Product from(InventoryState state) {
            return new Product(
                    state.productId(), state.name(), state.description(), state.price(), state.saleMode(),
                    state.presaleLimit(), state.active(), state.onHandQuantity(),
                    state.regularReservedQuantity(), state.availableRegularQuantity(),
                    state.presaleReservedQuantity(), state.createdAt(), state.updatedAt());
        }
    }

    public record Inventory(
            String productId,
            SaleMode saleMode,
            boolean active,
            int onHandQuantity,
            int regularReservedQuantity,
            int availableRegularQuantity,
            int presaleReservedQuantity,
            Integer presaleLimit,
            Instant updatedAt) {
        public static Inventory from(InventoryState state) {
            return new Inventory(
                    state.productId(), state.saleMode(), state.active(), state.onHandQuantity(),
                    state.regularReservedQuantity(), state.availableRegularQuantity(),
                    state.presaleReservedQuantity(), state.presaleLimit(), state.inventoryUpdatedAt());
        }
    }

    public record Movement(
            long movementId,
            String productId,
            String movementType,
            int physicalDelta,
            int regularReservedDelta,
            int presaleReservedDelta,
            int onHandAfter,
            int regularReservedAfter,
            int presaleReservedAfter,
            String referenceId,
            String reason,
            Instant createdAt) {}

    public record OrderItem(
            String productId,
            String productName,
            SaleMode saleMode,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {}

    public record Order(
            String orderId,
            String status,
            BigDecimal totalAmount,
            List<OrderItem> items,
            Instant createdAt,
            Instant updatedAt) {}

    public record OrderResult(Order order, boolean replayed) {}
}
