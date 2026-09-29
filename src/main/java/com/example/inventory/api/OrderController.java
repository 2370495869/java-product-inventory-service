package com.example.inventory.api;

import com.example.inventory.application.OrderService;
import com.example.inventory.domain.OrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "订单", description = "幂等创建订单、预留库存和取消未完成预留")
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    @Operation(summary = "创建订单并预留库存")
    public ResponseEntity<Views.Order> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody Requests.CreateOrder request) {
        Views.OrderResult result = orders.create(idempotencyKey, request);
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(result.order());
    }

    @GetMapping
    @Operation(summary = "分页查询订单")
    public Views.Page<Views.Order> page(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return orders.page(status, page, size);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "查询订单")
    public Views.Order get(@PathVariable String orderId) {
        return orders.get(orderId);
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "取消订单并释放库存预留")
    public Views.Order cancel(@PathVariable String orderId) {
        return orders.cancel(orderId);
    }
}
