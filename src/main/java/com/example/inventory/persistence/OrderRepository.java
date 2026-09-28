package com.example.inventory.persistence;

import com.example.inventory.domain.SaleMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OrderRepository {
    private static final RowMapper<Header> HEADER_MAPPER = OrderRepository::mapHeader;

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(UUID orderId, String key, String requestHash) {
        return jdbc.update("""
                INSERT INTO orders(order_id, idempotency_key, request_hash, status)
                VALUES (?, ?, ?, 'RESERVED')
                ON CONFLICT (idempotency_key) DO NOTHING
                """, orderId, key, requestHash) == 1;
    }

    public Optional<Header> findByKey(String key) {
        return jdbc.query("""
                SELECT order_id, idempotency_key, request_hash, status, created_at, updated_at
                FROM orders WHERE idempotency_key = ?
                """, HEADER_MAPPER, key).stream().findFirst();
    }

    public Optional<Header> findById(UUID orderId) {
        return jdbc.query("""
                SELECT order_id, idempotency_key, request_hash, status, created_at, updated_at
                FROM orders WHERE order_id = ?
                """, HEADER_MAPPER, orderId).stream().findFirst();
    }

    public Optional<Header> lockById(UUID orderId) {
        return jdbc.query("""
                SELECT order_id, idempotency_key, request_hash, status, created_at, updated_at
                FROM orders WHERE order_id = ? FOR UPDATE
                """, HEADER_MAPPER, orderId).stream().findFirst();
    }

    public void insertItem(UUID orderId, String productId, String productName,
                           SaleMode saleMode, int quantity, BigDecimal unitPrice) {
        jdbc.update("""
                INSERT INTO order_items(order_id, product_id, product_name, sale_mode, quantity, unit_price)
                VALUES (?, ?, ?, ?, ?, ?)
                """, orderId, productId, productName, saleMode.name(), quantity, unitPrice);
    }

    public List<Line> items(UUID orderId) {
        return jdbc.query("""
                SELECT product_id, product_name, sale_mode, quantity, unit_price
                FROM order_items WHERE order_id = ? ORDER BY product_id
                """, OrderRepository::mapLine, orderId);
    }

    public void updateStatus(UUID orderId, String status) {
        jdbc.update("UPDATE orders SET status = ?, updated_at = now() WHERE order_id = ?",
                status, orderId);
    }

    private static Header mapHeader(ResultSet rs, int row) throws SQLException {
        return new Header(
                rs.getObject("order_id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getString("status"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Line mapLine(ResultSet rs, int row) throws SQLException {
        return new Line(rs.getString("product_id"), rs.getString("product_name"),
                SaleMode.valueOf(rs.getString("sale_mode")), rs.getInt("quantity"),
                rs.getBigDecimal("unit_price"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record Header(UUID orderId, String idempotencyKey, String requestHash,
                         String status, Instant createdAt, Instant updatedAt) {}

    public record Line(String productId, String productName, SaleMode saleMode,
                       int quantity, BigDecimal unitPrice) {}
}
