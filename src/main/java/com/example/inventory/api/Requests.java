package com.example.inventory.api;

import com.example.inventory.domain.SaleMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public final class Requests {
    private Requests() {}

    public record CreateProduct(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String productId,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 2000) String description,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotNull SaleMode saleMode,
            @PositiveOrZero @Max(1_000_000_000) Integer initialStock,
            @Positive @Max(1_000_000_000) Integer presaleLimit) {}

    public record UpdateProduct(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 2000) String description,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotNull SaleMode saleMode,
            @Positive @Max(1_000_000_000) Integer presaleLimit,
            @NotNull Boolean active) {}

    public record StockAdjustment(
            @NotNull @Min(-1_000_000_000) @Max(1_000_000_000) Integer quantityDelta,
            @NotBlank @Size(max = 240) String reason) {}

    public record CreateOrder(
            @NotEmpty @Size(max = 50) List<@Valid OrderLine> items) {}

    public record OrderLine(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String productId,
            @NotNull @Min(1) @Max(100_000) Integer quantity) {}
}
