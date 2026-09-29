package com.example.inventory.application;

import com.example.inventory.api.Requests;
import com.example.inventory.api.Views;
import com.example.inventory.domain.ApiException;
import com.example.inventory.domain.SaleMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryWorkflowTest {
    private static final String DATABASE_PASSWORD = UUID.randomUUID().toString();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("inventory_test")
            .withUsername("inventory_test")
            .withPassword(DATABASE_PASSWORD);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ProductService products;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderService orders;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE TABLE orders, order_items, inventory_movements, inventory, products RESTART IDENTITY CASCADE");
    }

    @Test
    void idempotentReplayReturnsTheSameOrderAndDoesNotReserveTwice() {
        createProduct("REGULAR", 5, SaleMode.REGULAR, null);
        Requests.CreateOrder request = order("REGULAR", 2);

        Views.OrderResult first = orders.create("same-request", request);
        Views.OrderResult replay = orders.create("same-request", request);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.order().orderId(), replay.order().orderId());
        assertEquals(2, inventory.get("REGULAR").regularReservedQuantity());
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE movement_type = 'ORDER_RESERVED'"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> orders.create("same-request", order("REGULAR", 1))).status());
    }

    @Test
    void concurrentReplayWithTheSameKeyCreatesOnlyOneOrder() throws Exception {
        int workers = 12;
        createProduct("IDEMPOTENT", 20, SaleMode.REGULAR, null);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Views.OrderResult>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrency test did not start");
                    }
                    return orders.create("same-concurrent-request", order("IDEMPOTENT", 3));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<Views.OrderResult> completed = new ArrayList<>();
            for (Future<Views.OrderResult> result : results) {
                completed.add(result.get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS));
            }

            assertEquals(1, completed.stream().map(result -> result.order().orderId()).distinct().count());
            assertEquals(1, completed.stream().filter(result -> !result.replayed()).count());
            assertEquals(workers - 1, completed.stream().filter(Views.OrderResult::replayed).count());
            assertEquals(1, count("SELECT count(*) FROM orders WHERE idempotency_key = 'same-concurrent-request'"));
            assertEquals(3, inventory.get("IDEMPOTENT").regularReservedQuantity());
            assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE movement_type = 'ORDER_RESERVED'"));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void multiItemOrderRollsBackEveryReservationWhenOneItemIsUnavailable() {
        createProduct("REGULAR", 10, SaleMode.REGULAR, null);
        createProduct("EMPTY", 0, SaleMode.REGULAR, null);
        Requests.CreateOrder request = new Requests.CreateOrder(List.of(
                new Requests.OrderLine("EMPTY", 1), new Requests.OrderLine("REGULAR", 2)));

        assertEquals(409, assertThrows(ApiException.class,
                () -> orders.create("all-or-nothing", request)).status());

        assertEquals(0, inventory.get("REGULAR").regularReservedQuantity());
        assertEquals(0, inventory.get("EMPTY").regularReservedQuantity());
        assertEquals(0, count("SELECT count(*) FROM orders WHERE idempotency_key = 'all-or-nothing'"));
        assertEquals(0, count("SELECT count(*) FROM inventory_movements WHERE movement_type = 'ORDER_RESERVED'"));
    }

    @Test
    void presaleCapacityIsBoundedAndCancellationReleasesItOnce() {
        createProduct("PRESALE", 0, SaleMode.PRESALE, 5);
        Views.Order reserved = orders.create("presale-order", order("PRESALE", 5)).order();

        assertEquals(5, inventory.get("PRESALE").presaleReservedQuantity());
        assertEquals(409, assertThrows(ApiException.class,
                () -> orders.create("over-limit", order("PRESALE", 1))).status());

        assertEquals("CANCELLED", orders.cancel(reserved.orderId()).status());
        assertEquals("CANCELLED", orders.cancel(reserved.orderId()).status());
        assertEquals(0, inventory.get("PRESALE").presaleReservedQuantity());
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE movement_type = 'ORDER_CANCELLED'"));
    }

    @Test
    void orderHistoryIsPagedFilterableAndKeepsTheOrderLookupRoute() throws Exception {
        createProduct("HISTORY_A", 5, SaleMode.REGULAR, null);
        createProduct("HISTORY_B", 5, SaleMode.REGULAR, null);
        Views.Order first = orders.create("history-a", order("HISTORY_A", 2)).order();
        Views.Order second = orders.create("history-b", order("HISTORY_B", 1)).order();
        orders.cancel(first.orderId());

        HttpResponse<String> pageOne = get("/api/orders?page=0&size=1");
        HttpResponse<String> pageTwo = get("/api/orders?page=1&size=1");
        assertEquals(200, pageOne.statusCode());
        assertEquals(200, pageTwo.statusCode());
        assertTrue(pageOne.body().contains("\"totalElements\":2"));
        assertTrue(pageOne.body().contains("\"totalPages\":2"));
        String firstPageOrderId = orderIdOnPage(pageOne.body());
        String secondPageOrderId = orderIdOnPage(pageTwo.body());
        assertFalse(firstPageOrderId.equals(secondPageOrderId));
        assertTrue(List.of(first.orderId(), second.orderId())
                .containsAll(List.of(firstPageOrderId, secondPageOrderId)));

        HttpResponse<String> reserved = get("/api/orders?status=RESERVED");
        HttpResponse<String> cancelled = get("/api/orders?status=CANCELLED");
        assertTrue(reserved.body().contains("\"totalElements\":1"));
        assertTrue(cancelled.body().contains("\"totalElements\":1"));
        assertTrue(reserved.body().contains(second.orderId()));
        assertTrue(cancelled.body().contains(first.orderId()));
        assertEquals(400, get("/api/orders?status=PAID").statusCode());

        HttpResponse<String> detail = get("/api/orders/" + first.orderId());
        assertEquals(200, detail.statusCode());
        assertTrue(detail.body().contains("\"orderId\":\"" + first.orderId() + "\""));
        assertTrue(detail.body().contains("\"status\":\"CANCELLED\""));
        assertTrue(detail.body().contains("\"items\":[{"));

        HttpResponse<String> emptyPage = get("/api/orders?page=10&size=1");
        assertEquals(200, emptyPage.statusCode());
        assertTrue(emptyPage.body().contains("\"items\":[]"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        Integer port = environment.getRequiredProperty("local.server.port", Integer.class);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String orderIdOnPage(String body) {
        Matcher matcher = Pattern.compile("\\\"orderId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    @Test
    void concurrentOrdersNeverReserveMoreThanPhysicalStock() throws Exception {
        int stock = 12;
        int workers = 30;
        createProduct("RACE", stock, SaleMode.REGULAR, null);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                int requestNumber = i;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrency test did not start");
                    }
                    try {
                        orders.create("race-" + requestNumber, order("RACE", 1));
                        return true;
                    } catch (ApiException exception) {
                        if (exception.status() == 409) {
                            return false;
                        }
                        throw exception;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            long successfulOrders = 0;
            for (Future<Boolean> result : results) {
                if (result.get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) {
                    successfulOrders++;
                }
            }

            assertEquals(stock, successfulOrders);
            assertEquals(stock, inventory.get("RACE").regularReservedQuantity());
            assertEquals(0, inventory.get("RACE").availableRegularQuantity());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void createProduct(String id, int stock, SaleMode mode, Integer presaleLimit) {
        products.create(new Requests.CreateProduct(id, id, null, new BigDecimal("10.00"),
                mode, stock, presaleLimit));
    }

    private static Requests.CreateOrder order(String productId, int quantity) {
        return new Requests.CreateOrder(List.of(new Requests.OrderLine(productId, quantity)));
    }

    private long count(String sql) {
        Long result = jdbc.queryForObject(sql, Long.class);
        return result == null ? 0 : result;
    }
}
