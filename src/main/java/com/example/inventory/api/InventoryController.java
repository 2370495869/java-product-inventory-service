package com.example.inventory.api;

import com.example.inventory.application.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory/{productId}")
@Tag(name = "库存", description = "实物库存调整、库存预留概览和不可变库存流水")
public class InventoryController {
    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    @Operation(summary = "查询库存概览")
    public Views.Inventory get(@PathVariable String productId) {
        return inventory.get(productId);
    }

    @PostMapping("/adjustments")
    @Operation(summary = "调整实物库存")
    public Views.Inventory adjust(
            @PathVariable String productId,
            @Valid @RequestBody Requests.StockAdjustment request) {
        return inventory.adjust(productId, request);
    }

    @GetMapping("/movements")
    @Operation(summary = "查询库存流水")
    public Views.Page<Views.Movement> movements(
            @PathVariable String productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return inventory.movements(productId, page, size);
    }
}
