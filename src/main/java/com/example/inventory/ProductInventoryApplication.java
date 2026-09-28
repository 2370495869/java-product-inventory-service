package com.example.inventory;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "商品与库存服务 API",
        version = "v1",
        description = "商品目录、库存流水、普通销售、限额预售和订单库存预留"))
public class ProductInventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductInventoryApplication.class, args);
    }
}
