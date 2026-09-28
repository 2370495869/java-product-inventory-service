package com.example.inventory.api;

import com.example.inventory.application.ProductService;
import com.example.inventory.domain.SaleMode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/products")
@Tag(name = "商品", description = "商品创建、更新、停用、查询和搜索")
public class ProductController {
    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    @PostMapping
    @Operation(summary = "创建商品")
    public ResponseEntity<Views.Product> create(@Valid @RequestBody Requests.CreateProduct request) {
        Views.Product created = products.create(request);
        return ResponseEntity.created(URI.create("/api/products/" + created.productId())).body(created);
    }

    @GetMapping("/{productId}")
    @Operation(summary = "查询商品及库存")
    public Views.Product get(@PathVariable String productId) {
        return products.get(productId);
    }

    @GetMapping
    @Operation(summary = "搜索商品并分页")
    public Views.Page<Views.Product> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) SaleMode saleMode,
            @RequestParam(required = false, defaultValue = "true") Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return products.search(q, saleMode, active, page, size);
    }

    @PutMapping("/{productId}")
    @Operation(summary = "更新商品信息和销售规则")
    public Views.Product update(
            @PathVariable String productId,
            @Valid @RequestBody Requests.UpdateProduct request) {
        return products.update(productId, request);
    }

    @DeleteMapping("/{productId}")
    @Operation(summary = "停用商品")
    public ResponseEntity<Void> deactivate(@PathVariable String productId) {
        products.deactivate(productId);
        return ResponseEntity.noContent().build();
    }
}
