package com.example.inventory.persistence;

import com.example.inventory.domain.InventoryState;
import com.example.inventory.domain.SaleMode;
import com.example.inventory.api.Views;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InventoryRepository {
    private static final String STATE_SELECT = """
            SELECT p.product_id, p.name, p.description, p.price, p.sale_mode, p.presale_limit,
                   p.active, p.created_at, p.updated_at,
                   i.on_hand_quantity, i.regular_reserved_quantity, i.presale_reserved_quantity,
                   i.updated_at AS inventory_updated_at
            FROM products p
            JOIN inventory i ON i.product_id = p.product_id
            """;

    private static final RowMapper<InventoryState> STATE_MAPPER = InventoryRepository::mapState;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public InventoryRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    public Optional<InventoryState> find(String productId) {
        return jdbc.query(STATE_SELECT + " WHERE p.product_id = ?", STATE_MAPPER, productId)
                .stream().findFirst();
    }

    public Optional<InventoryState> lock(String productId) {
        return jdbc.query(STATE_SELECT + " WHERE p.product_id = ? FOR UPDATE OF p, i", STATE_MAPPER, productId)
                .stream().findFirst();
    }

    public void insertProduct(
            String productId, String name, String description, java.math.BigDecimal price,
            SaleMode saleMode, Integer presaleLimit, int initialStock) {
        jdbc.update("""
                INSERT INTO products(product_id, name, description, price, sale_mode, presale_limit)
                VALUES (?, ?, ?, ?, ?, ?)
                """, productId, name, description, price, saleMode.name(), presaleLimit);
        jdbc.update("""
                INSERT INTO inventory(product_id, on_hand_quantity)
                VALUES (?, ?)
                """, productId, initialStock);
    }

    public void updateProduct(
            String productId, String name, String description, java.math.BigDecimal price,
            SaleMode saleMode, Integer presaleLimit, boolean active) {
        jdbc.update("""
                UPDATE products
                SET name = ?, description = ?, price = ?, sale_mode = ?, presale_limit = ?,
                    active = ?, updated_at = now()
                WHERE product_id = ?
                """, name, description, price, saleMode.name(), presaleLimit, active, productId);
    }

    public void deactivate(String productId) {
        jdbc.update("UPDATE products SET active = false, updated_at = now() WHERE product_id = ?", productId);
    }

    public List<InventoryState> search(
            String query, SaleMode saleMode, Boolean active, int offset, int limit) {
        QueryFilter filter = filter(query, saleMode, active);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValues(filter.parameters())
                .addValue("limit", limit)
                .addValue("offset", offset);
        return namedJdbc.query(STATE_SELECT + filter.whereClause()
                        + " ORDER BY p.product_id LIMIT :limit OFFSET :offset",
                params, STATE_MAPPER);
    }

    public long count(String query, SaleMode saleMode, Boolean active) {
        QueryFilter filter = filter(query, saleMode, active);
        Long count = namedJdbc.queryForObject(
                "SELECT count(*) FROM products p" + filter.whereClause(),
                new MapSqlParameterSource().addValues(filter.parameters()), Long.class);
        return count == null ? 0 : count;
    }

    public boolean adjustPhysical(String productId, int delta) {
        return jdbc.update("""
                UPDATE inventory
                SET on_hand_quantity = on_hand_quantity + ?, updated_at = now()
                WHERE product_id = ?
                  AND on_hand_quantity + ? >= regular_reserved_quantity
                  AND on_hand_quantity + ? >= 0
                """, delta, productId, delta, delta) == 1;
    }

    public boolean reserveRegular(String productId, int quantity) {
        return jdbc.update("""
                UPDATE inventory
                SET regular_reserved_quantity = regular_reserved_quantity + ?, updated_at = now()
                WHERE product_id = ?
                  AND on_hand_quantity - regular_reserved_quantity >= ?
                """, quantity, productId, quantity) == 1;
    }

    public boolean reservePresale(String productId, int quantity, int presaleLimit) {
        return jdbc.update("""
                UPDATE inventory
                SET presale_reserved_quantity = presale_reserved_quantity + ?, updated_at = now()
                WHERE product_id = ?
                  AND presale_reserved_quantity + ? <= ?
                """, quantity, productId, quantity, presaleLimit) == 1;
    }

    public boolean releaseRegular(String productId, int quantity) {
        return jdbc.update("""
                UPDATE inventory
                SET regular_reserved_quantity = regular_reserved_quantity - ?, updated_at = now()
                WHERE product_id = ? AND regular_reserved_quantity >= ?
                """, quantity, productId, quantity) == 1;
    }

    public boolean releasePresale(String productId, int quantity) {
        return jdbc.update("""
                UPDATE inventory
                SET presale_reserved_quantity = presale_reserved_quantity - ?, updated_at = now()
                WHERE product_id = ? AND presale_reserved_quantity >= ?
                """, quantity, productId, quantity) == 1;
    }

    public void insertMovement(
            InventoryState state, String type, int physicalDelta, int regularReservedDelta,
            int presaleReservedDelta, UUID referenceId, String reason) {
        jdbc.update("""
                INSERT INTO inventory_movements(
                    product_id, movement_type, physical_delta, regular_reserved_delta,
                    presale_reserved_delta, on_hand_after, regular_reserved_after,
                    presale_reserved_after, reference_id, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, state.productId(), type, physicalDelta, regularReservedDelta,
                presaleReservedDelta, state.onHandQuantity(), state.regularReservedQuantity(),
                state.presaleReservedQuantity(), referenceId, reason);
    }

    public List<Views.Movement> movements(String productId, int offset, int limit) {
        return jdbc.query("""
                SELECT movement_id, product_id, movement_type, physical_delta,
                       regular_reserved_delta, presale_reserved_delta, on_hand_after,
                       regular_reserved_after, presale_reserved_after, reference_id, reason, created_at
                FROM inventory_movements
                WHERE product_id = ?
                ORDER BY movement_id DESC
                LIMIT ? OFFSET ?
                """, InventoryRepository::mapMovement, productId, limit, offset);
    }

    public long countMovements(String productId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE product_id = ?", Long.class, productId);
        return count == null ? 0 : count;
    }

    private static QueryFilter filter(String query, SaleMode saleMode, Boolean active) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (query != null && !query.isBlank()) {
            where.append(" AND (p.product_id ILIKE :query OR p.name ILIKE :query)");
            params.addValue("query", "%" + query.trim() + "%");
        }
        if (saleMode != null) {
            where.append(" AND p.sale_mode = :saleMode");
            params.addValue("saleMode", saleMode.name());
        }
        if (active != null) {
            where.append(" AND p.active = :active");
            params.addValue("active", active);
        }
        return new QueryFilter(where.toString(), params.getValues());
    }

    private static InventoryState mapState(ResultSet rs, int row) throws SQLException {
        Integer presaleLimit = (Integer) rs.getObject("presale_limit");
        return new InventoryState(
                rs.getString("product_id"), rs.getString("name"), rs.getString("description"),
                rs.getBigDecimal("price"), SaleMode.valueOf(rs.getString("sale_mode")), presaleLimit,
                rs.getBoolean("active"), rs.getInt("on_hand_quantity"),
                rs.getInt("regular_reserved_quantity"), rs.getInt("presale_reserved_quantity"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "inventory_updated_at"));
    }

    private static Views.Movement mapMovement(ResultSet rs, int row) throws SQLException {
        return new Views.Movement(
                rs.getLong("movement_id"), rs.getString("product_id"), rs.getString("movement_type"),
                rs.getInt("physical_delta"), rs.getInt("regular_reserved_delta"),
                rs.getInt("presale_reserved_delta"), rs.getInt("on_hand_after"),
                rs.getInt("regular_reserved_after"), rs.getInt("presale_reserved_after"),
                rs.getString("reference_id"), rs.getString("reason"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record QueryFilter(String whereClause, Map<String, Object> parameters) {}
}
