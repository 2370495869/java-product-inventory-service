INSERT INTO products(product_id, name, description, price, sale_mode, presale_limit)
VALUES
    ('SKU-1001', '轻薄笔记本', '普通销售示例商品', 5999.00, 'REGULAR', NULL),
    ('SKU-1002', '无线鼠标', '库存调整与普通销售示例商品', 99.00, 'REGULAR', NULL),
    ('SKU-1003', '机械键盘新品', '限额预售示例商品；预售名额不代表实物库存', 699.00, 'PRESALE', 50);

INSERT INTO inventory(product_id, on_hand_quantity)
VALUES ('SKU-1001', 20), ('SKU-1002', 100), ('SKU-1003', 0);

INSERT INTO inventory_movements(
    product_id, movement_type, physical_delta, regular_reserved_delta, presale_reserved_delta,
    on_hand_after, regular_reserved_after, presale_reserved_after, reason)
VALUES
    ('SKU-1001', 'INITIAL_STOCK', 20, 0, 0, 20, 0, 0, '演示数据：初始实物库存'),
    ('SKU-1002', 'INITIAL_STOCK', 100, 0, 0, 100, 0, 0, '演示数据：初始实物库存');
