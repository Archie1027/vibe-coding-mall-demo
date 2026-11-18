package com.zhicai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.CheckoutCatalogMapper;
import com.zhicai.platform.catalog.CheckoutCatalogService;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.inventory.*;
import com.zhicai.platform.trade.*;
import com.zhicai.platform.write.WriteRequestFingerprint;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Isolated localhost HTTP + MySQL. Auth lookup is a test double bound to existing identities. */
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M1_PAYMENT_MODE", matches = "read-only|approved-single-payment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "spring.flyway.enabled=false", "spring.sql.init.mode=never",
    "app.dev-bootstrap.enabled=false", "app.catalog.writes-enabled=false", "app.customer.writes-enabled=false",
    "app.order.writes-enabled=false", "app.order.expiry-enabled=false", "app.payment.mock-enabled=true"})
@Import(MySqlMockPaymentHttpIntegrationTest.GuardConfiguration.class)
class MySqlMockPaymentHttpIntegrationTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/zhicai_dev?connectionTimeZone=UTC";
    private static final Path BASELINE = Path.of(".local/acceptance");
    private static final String BASELINE_SHA = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String START = "/api/v1/payments";
    private static final List<String> DELETE_ORDER = List.of("payment_event", "payment", "stock_ledger", "stock_reservation",
        "sales_order_item", "sales_order", "checkout_quote", "stock_balance", "customer_address", "idempotency_request", "audit_log");
    static final Map<String, Integer> CAPS = Map.ofEntries(
        Map.entry("INSERT customer_address", 1), Map.entry("INSERT stock_balance", 1),
        Map.entry("INSERT checkout_quote", 1), Map.entry("INSERT sales_order", 1),
        Map.entry("INSERT sales_order_item", 1), Map.entry("INSERT stock_reservation", 1), Map.entry("INSERT stock_ledger", 1),
        Map.entry("INSERT payment", 1), Map.entry("INSERT payment_event", 2), Map.entry("INSERT idempotency_request", 5),
        Map.entry("INSERT audit_log", 3), Map.entry("UPDATE stock_balance", 1), Map.entry("UPDATE checkout_quote", 1),
        Map.entry("UPDATE payment", 2), Map.entry("UPDATE sales_order", 2), Map.entry("UPDATE idempotency_request", 4));
    static final int MAX_DELETES = 17;
    private static final AtomicBoolean FAIL_NEXT_PAID = new AtomicBoolean();
    private static final AtomicBoolean INJECTED = new AtomicBoolean();
    private static final MySqlAcceptanceSupport.SqlGuard GUARD =
        new MySqlAcceptanceSupport.SqlGuard(CAPS, DELETE_ORDER, MAX_DELETES, MySqlMockPaymentHttpIntegrationTest::approved) {
            @Override void afterExecution(String sql, String kind, Object result, Statement driver) throws SQLException {
                if ("UPDATE sales_order".equals(kind) && sql.contains("state='PAID'") && driver.getUpdateCount() == 1
                    && FAIL_NEXT_PAID.compareAndSet(true, false)) {
                    INJECTED.set(true);
                    throw new SQLException("Test-only failure after the payment order UPDATE reached MySQL", "45000");
                }
            }
        };

    private static boolean approved() {
        String approval = System.getenv("M1_PAYMENT_APPROVAL");
        return "approved-single-payment".equals(System.getenv("M1_PAYMENT_MODE")) && approval != null && !approval.isBlank();
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        if (!URL.equals(System.getenv("DB_URL"))) throw new IllegalStateException("Canonical local UTC target required");
        p.add("spring.datasource.url", () -> URL);
        p.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        p.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        p.add("spring.datasource.hikari.connection-init-sql", () -> "SET time_zone = '+00:00'");
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        p.add("app.catalog.replay-key", () -> encoded);
    }

    @TestConfiguration static class GuardConfiguration {
        @Bean static BeanPostProcessor guard() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ds ? GUARD.dataSource(ds) : bean;
                }
            };
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @Autowired IdentityMapper identities;
    @Autowired CheckoutCatalogMapper catalogMapper;
    @Autowired InventoryAvailabilityMapper availabilityMapper;
    @Autowired StockMapper stockMapper;
    @Autowired QuoteMapper quoteMapper;
    @Autowired AddressMapper addressMapper;
    @Autowired OrderMapper orderMapper;
    @MockitoBean AuthService auth;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3)).build();
    private final String run = UUID.randomUUID().toString();
    private final String label = "付款验收-" + run;
    private final String buyerToken = UUID.randomUUID().toString(), staffToken = UUID.randomUUID().toString();
    private final String noGrantToken = UUID.randomUUID().toString();
    private final Map<String, List<String>> columns = new TreeMap<>();
    private final Map<String, Claim> claims = new LinkedHashMap<>();
    private final Map<String, ExpectedAudit> audits = new LinkedHashMap<>();
    private JsonNode reference;
    private Path manifest;
    private String owner, staff, warehouse, sku, address, balance, quote, payment;
    private OrderModels.Order order;

    @Test void singleApprovedMockPaymentScenario() throws Exception {
        Path root = Path.of(System.getenv("M1_PAYMENT_EVIDENCE"));
        manifest = root.resolve("manifest.jsonl");
        Files.createFile(manifest);
        prepare();
        securityPreflight();
        assertThat(GUARD.snapshot()).isEmpty();
        assertThat(GUARD.locks.get()).isZero();
        if (!approved()) {
            assertThat(System.getenv("M1_PAYMENT_MODE")).isEqualTo("read-only");
            baseline(false);
            note("read-only-verified");
            return;
        }
        try {
            verifyMarker(root);
            note("write-intent");
            GUARD.armed = true;
            seed();
            Map<String, List<Map<String, String>>> stockBefore = tx(true).execute(s -> snapshot(List.of("stock_balance", "stock_reservation", "stock_ledger"), false));
            exercisePayment();
            var frozen = Objects.requireNonNull(tx(true).execute(s -> validate(stockBefore)));
            baseline(true);
            var proofs = new TreeMap<String, List<Map<String, String>>>();
            frozen.forEach((t, rows) -> proofs.put(t, rows.stream().map(row -> Map.of("id", row.get("id"), "fullRowHash", row.get("_guard_hash"))).toList()));
            Files.writeString(root.resolve("cleanup-proofs.json"), json.writeValueAsString(proofs), StandardOpenOption.CREATE_NEW);
            assertThat(GUARD.snapshot()).containsExactlyInAnyOrderEntriesOf(CAPS);
            note("checks-passed");
            cleanup(frozen);
            assertThat(GUARD.deletes.get()).isEqualTo(MAX_DELETES);
            baseline(false);
            note("cleaned-and-verified");
        } catch (Exception | AssertionError failure) {
            note("retained-after-failure");
            throw failure;
        } finally {
            FAIL_NEXT_PAID.set(false);
            GUARD.armed = false;
            GUARD.cleanup = false;
        }
    }

    private void prepare() throws Exception {
        byte[] bytes = Files.readAllBytes(BASELINE);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(BASELINE_SHA);
        reference = json.readTree(bytes).path("Rows");
        assertThat(reference).hasSize(27);
        for (var row : reference) {
            String table = row.path("table").asText();
            assertThat(table).matches("[a-z_]+");
            assertThat(columns.put(table, jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",
                String.class, table))).isNull();
            assertThat(columns.get(table)).isNotEmpty().allMatch(name -> name.matches("[a-z_]+"));
        }
        baseline(false);
        for (String table : DELETE_ORDER) {
            assertThat(columns.get(table)).contains("id");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class)).isZero();
        }
        var buyer = identities.findByUsername(AccountType.CUSTOMER, "customer");
        var operator = identities.findByUsername(AccountType.STAFF, "admin");
        assertThat(buyer).isNotNull();
        assertThat(operator).isNotNull();
        owner = buyer.id();
        staff = operator.id();
        var grants = identities.permissions(staff);
        assertThat(buyer.enabled() && operator.enabled()).isTrue();
        assertThat(grants).contains("system:write");
        when(auth.authenticate(buyerToken)).thenReturn(actor(buyer, List.of()));
        when(auth.authenticate(staffToken)).thenReturn(actor(operator, grants));
        when(auth.authenticate(noGrantToken)).thenReturn(actor(operator, List.of()));
        warehouse = jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM warehouse WHERE enabled=1 AND fulfillment_enabled=1 ORDER BY priority,id LIMIT 1", String.class);
        sku = jdbc.queryForObject("""
            SELECT CAST(s.id AS CHAR) FROM sku s JOIN product p ON p.id=s.product_id
            JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id
            WHERE s.enabled=1 AND p.state='ON_SALE' AND c.enabled=1 AND b.enabled=1 ORDER BY s.id LIMIT 1
            """, String.class);
    }

    private void verifyMarker(Path root) throws Exception {
        var marker = json.readTree(Files.readAllBytes(Path.of(System.getenv("M1_PAYMENT_ATTEMPT"))));
        assertThat(marker.path("Identity").asText()).isEqualTo(System.getenv("M1_PAYMENT_IDENTITY")).matches("[0-9A-F]{64}");
        assertThat(marker.path("ApprovalId").asText()).isEqualTo(System.getenv("M1_PAYMENT_APPROVAL"));
        assertThat(marker.path("Invocation").asText()).isEqualTo(root.toString());
    }

    private void securityPreflight() throws Exception {
        String start = "{\"orderId\":\"5\",\"channel\":\"MOCK\"}";
        expect(post(START, null, null, start), 401, "UNAUTHENTICATED");
        expect(post(START, staffToken, null, start), 403, "FORBIDDEN");
        expect(post(START, buyerToken, null, start), 400, "VALIDATION_ERROR");
        var route = "/api/v1/admin/payments/5/mock-events";
        expect(post(route, buyerToken, null, "{}"), 403, "FORBIDDEN");
        expect(post(route, noGrantToken, null, "{}"), 403, "FORBIDDEN");
        expect(post(route, staffToken, null, "{}"), 400, "VALIDATION_ERROR");
    }

    private void seed() {
        Clock clock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        var catalog = new CheckoutCatalogService(catalogMapper, json);
        var availability = new InventoryAvailabilityService(availabilityMapper);
        var quotes = new QuoteService(quoteMapper, addressMapper, catalog, availability, json, clock);
        var orders = new OrderService(orderMapper, catalog, availability, new InventoryReservationService(stockMapper), json, clock);
        tx(false).executeWithoutResult(s -> {
            assertThat(jdbc.update("""
                INSERT INTO customer_address(customer_id,recipient,phone,province,city,district,detail,is_default)
                VALUES(CAST(? AS UNSIGNED),?,'10000000007','测试省','测试市','测试区',?,FALSE)
                """, owner, label, label)).isEqualTo(1);
            address = jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM customer_address WHERE customer_id=CAST(? AS UNSIGNED) AND BINARY recipient=BINARY ?", String.class, owner, label);
            assertThat(jdbc.update("""
                INSERT INTO stock_balance(warehouse_id,sku_id,on_hand,reserved,quarantine)
                VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),2,0,0)
                """, warehouse, sku)).isEqualTo(1);
            balance = jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM stock_balance WHERE warehouse_id=CAST(? AS UNSIGNED) AND sku_id=CAST(? AS UNSIGNED)", String.class, warehouse, sku);
            quote = quotes.create(owner, new QuoteModels.Request(address, List.of(new QuoteModels.Item(sku, 1)), null)).id();
            order = orders.create(owner, new OrderModels.Create(quote, label), "seed:" + run);
        });
    }

    private void exercisePayment() throws Exception {
        String start = json.createObjectNode().put("orderId", order.id()).put("channel", "MOCK").toString();
        String startKey = run + "-start";
        var response = post(START, buyerToken, startKey, start);
        var started = expect(response, 201, "OK");
        payment = started.path("data").path("id").asText();
        assertThat(payment).matches("[1-9][0-9]{0,18}");
        assertThat(started.path("data").path("orderId").asText()).isEqualTo(order.id());
        assertThat(started.path("data").path("state").asText()).isEqualTo("PROCESSING");
        assertThat(started.path("data").path("amountMinor").asText()).isEqualTo(order.payableMinor());
        audit(started, owner, "PAYMENT_CREATE", "PROCESSING");
        claim(startKey, "CUSTOMER:" + owner, START, start, "COMPLETED", "201");
        assertThat(post(START, buyerToken, startKey, start).body()).isEqualTo(response.body());

        String route = "/api/v1/admin/payments/" + payment + "/mock-events";
        var body = json.createObjectNode().put("eventId", run + "-event").put("result", "SUCCEEDED")
            .put("channelTransactionId", run + "-txn").put("amountMinor", order.payableMinor()).put("currency", "CNY");
        String good = body.toString();
        String wrong = body.deepCopy().put("amountMinor", "1".equals(order.payableMinor()) ? "2" : "1").toString();
        expect(post(route, staffToken, run + "-wrong", wrong), 409, "PAYMENT_AMOUNT_MISMATCH");
        claim(run + "-wrong", "STAFF:" + staff, route, wrong, "COMPLETED", "409");
        assertUnpaidWithoutEvent();

        FAIL_NEXT_PAID.set(true);
        try {
            expect(post(route, staffToken, run + "-inject", good), 503, "DEPENDENCY_UNAVAILABLE");
        } finally {
            FAIL_NEXT_PAID.set(false);
        }
        assertThat(INJECTED.get()).isTrue();
        claim(run + "-inject", "STAFF:" + staff, route, good, "IN_PROGRESS", null);
        assertUnpaidWithoutEvent();
        note("rollback-verified");

        var accepted = post(route, staffToken, run + "-success", good);
        var succeeded = expect(accepted, 200, "OK");
        assertThat(succeeded.path("data").path("state").asText()).isEqualTo("SUCCEEDED");
        audit(succeeded, staff, "PAYMENT_MOCK_SUCCESS", "SUCCEEDED");
        claim(run + "-success", "STAFF:" + staff, route, good, "COMPLETED", "200");
        assertThat(post(route, staffToken, run + "-success", good).body()).isEqualTo(accepted.body());
        expect(post(route, staffToken, run + "-success", wrong), 409, "IDEMPOTENCY_CONFLICT");
        var duplicate = expect(post(route, staffToken, run + "-same-event", good), 200, "OK");
        assertThat(duplicate.path("data")).isEqualTo(succeeded.path("data"));
        audit(duplicate, staff, "PAYMENT_MOCK_SUCCESS", "SUCCEEDED");
        claim(run + "-same-event", "STAFF:" + staff, route, good, "COMPLETED", "200");
    }

    private void assertUnpaidWithoutEvent() {
        assertThat(jdbc.queryForObject("SELECT state FROM sales_order WHERE id=CAST(? AS UNSIGNED)", String.class, order.id())).isEqualTo("UNPAID");
        assertThat(jdbc.queryForObject("SELECT state FROM payment WHERE id=CAST(? AS UNSIGNED)", String.class, payment)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_event", Integer.class)).isZero();
    }

    private Map<String, List<Map<String, String>>> validate(Map<String, List<Map<String, String>>> stockBefore) {
        var rows = snapshot(DELETE_ORDER, false);
        rows.forEach((table, values) -> {
            int expected = table.equals("payment_event") ? 1 : CAPS.get("INSERT " + table);
            assertThat(values).as(table).hasSize(expected).allSatisfy(row -> {
                assertThat(row.get("id")).matches("[1-9][0-9]{0,18}");
                assertThat(row.get("_guard_hash")).matches("[a-f0-9]{64}");
            });
        });
        stockBefore.forEach((table, expected) -> assertThat(rows.get(table)).as(table).isEqualTo(expected));
        one(rows, "customer_address").containsEntry("id", address).containsEntry("customer_id", owner).containsEntry("recipient", label);
        one(rows, "stock_balance").containsEntry("id", balance).containsEntry("warehouse_id", warehouse).containsEntry("sku_id", sku)
            .containsEntry("on_hand", "2").containsEntry("reserved", "1");
        one(rows, "checkout_quote").containsEntry("id", quote).containsEntry("customer_id", owner).containsEntry("used_order_id", order.id());
        one(rows, "sales_order").containsEntry("id", order.id()).containsEntry("customer_id", owner).containsEntry("warehouse_id", warehouse)
            .containsEntry("quote_id", quote).containsEntry("remark", label).containsEntry("state", "PAID").containsEntry("version", "1");
        assertThat(rows.get("sales_order").getFirst().get("paid_at")).isNotBlank();
        var line = rows.get("sales_order_item").getFirst().get("id");
        one(rows, "sales_order_item").containsEntry("order_id", order.id()).containsEntry("sku_id", sku).containsEntry("quantity", "1");
        one(rows, "stock_reservation").containsEntry("business_line_id", line).containsEntry("business_type", "ORDER")
            .containsEntry("state", "HELD").containsEntry("warehouse_id", warehouse).containsEntry("sku_id", sku).containsEntry("quantity", "1");
        one(rows, "stock_ledger").containsEntry("biz_id", order.id()).containsEntry("biz_line_id", line).containsEntry("action", "RESERVE")
            .containsEntry("trace_id", "seed:" + run).containsEntry("sku_id", sku);
        one(rows, "payment").containsEntry("id", payment).containsEntry("order_id", order.id()).containsEntry("channel", "MOCK")
            .containsEntry("state", "SUCCEEDED").containsEntry("amount_minor", order.payableMinor()).containsEntry("currency", "CNY")
            .containsEntry("channel_transaction_id", run + "-txn").containsEntry("version", "1");
        one(rows, "payment_event").containsEntry("payment_id", payment).containsEntry("channel", "MOCK")
            .containsEntry("event_id", run + "-event").containsEntry("event_type", "PAYMENT_SUCCESS")
            .containsEntry("payload_hash", MySqlAcceptanceSupport.sha(String.join("|", payment, run + "-event", run + "-txn", order.payableMinor(), "CNY")));
        verifyMetadata(rows);
        assertThat(rows.values().stream().mapToInt(List::size).sum()).isEqualTo(MAX_DELETES);
        return rows;
    }

    private void verifyMetadata(Map<String, List<Map<String, String>>> rows) {
        assertThat(rows.get("idempotency_request")).extracting(r -> r.get("key_hash"))
            .containsExactlyInAnyOrderElementsOf(claims.keySet());
        for (var row : rows.get("idempotency_request")) {
            Claim claim = claims.get(row.get("key_hash"));
            assertThat(row).containsEntry("actor_scope", claim.actor()).containsEntry("method", "POST")
                .containsEntry("request_hash", claim.requestHash()).containsEntry("route", claim.route())
                .containsEntry("state", claim.state()).containsEntry("http_status", claim.status());
            if (claim.status() == null) assertThat(row.get("encrypted_response")).isNull();
            else assertThat(row.get("encrypted_response")).isNotEmpty();
        }
        assertThat(rows.get("audit_log")).extracting(r -> r.get("trace_id")).containsExactlyInAnyOrderElementsOf(audits.keySet());
        for (var row : rows.get("audit_log")) {
            ExpectedAudit audit = audits.get(row.get("trace_id"));
            assertThat(row).containsEntry("actor_id", audit.actor()).containsEntry("action", audit.action())
                .containsEntry("resource_type", "PAYMENT").containsEntry("resource_id", payment).containsEntry("result", "SUCCESS");
            try {
                assertThat(json.readTree(row.get("masked_before_after"))).isEqualTo(json.createObjectNode().putNull("before")
                    .set("after", json.createObjectNode().put("id", payment).put("state", audit.state())));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Invalid persisted audit JSON", e);
            }
        }
    }

    private void cleanup(Map<String, List<Map<String, String>>> frozen) {
        var transaction = tx(false);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.executeWithoutResult(s -> {
            assertThat(snapshot(DELETE_ORDER.stream().sorted().toList(), true)).isEqualTo(frozen);
            GUARD.cleanup = true;
            try {
                for (String table : DELETE_ORDER) {
                    String sql = "DELETE FROM `" + table + "` WHERE id=CAST(? AS UNSIGNED) AND " + MySqlAcceptanceSupport.hashSql(columns.get(table)) + "=?";
                    for (var row : frozen.get(table))
                        assertThat(jdbc.update(sql, row.get("id"), row.get("_guard_hash"))).as(table).isEqualTo(1);
                }
            } finally {
                GUARD.cleanup = false;
            }
        });
    }

    private void baseline(boolean nonResourcesOnly) {
        tx(true).executeWithoutResult(s -> {
            assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'", String.class))
                .containsExactlyInAnyOrderElementsOf(columns.keySet());
            for (var row : reference) {
                String table = row.path("table").asText();
                if (nonResourcesOnly && DELETE_ORDER.contains(table)) continue;
                int count = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
                assertThat(count).as(table).isEqualTo(row.path("count").asInt());
                assertThat(MySqlAcceptanceSupport.referenceDigest(jdbc, table, columns.get(table), count))
                    .as(table).isEqualTo(row.path("sha256").asText());
            }
        });
    }

    private Map<String, List<Map<String, String>>> snapshot(List<String> tables, boolean lock) {
        var rows = new TreeMap<String, List<Map<String, String>>>();
        for (String table : tables) rows.put(table, MySqlAcceptanceSupport.rows(jdbc, table, columns.get(table), lock));
        return rows;
    }

    private static org.assertj.core.api.MapAssert<String, String> one(Map<String, List<Map<String, String>>> rows, String table) {
        return assertThat(rows.get(table).getFirst()).as(table);
    }

    private TransactionTemplate tx(boolean readOnly) {
        var t = new TransactionTemplate(manager);
        t.setReadOnly(readOnly);
        t.setIsolationLevel(readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_READ_COMMITTED);
        t.setTimeout(30);
        return t;
    }

    private static AccountView actor(AccountRow row, List<String> grants) {
        return new AccountView(row.id(), row.username(), row.displayName(), row.accountType(), grants, List.of(), List.of(), true, row.version());
    }

    private HttpResponse<String> post(String route, String token, String key, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route))
            .timeout(Duration.ofSeconds(12)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(code).isEqualTo(status);
        var envelope = json.readTree(response.body());
        assertThat(envelope.path("code").asText()).isEqualTo(code);
        return envelope;
    }

    private void claim(String key, String actor, String route, String body, String state, String status) throws Exception {
        var fingerprint = new WriteRequestFingerprint(json);
        assertThat(claims.put(fingerprint.keyHash(key), new Claim(actor, route, fingerprint.requestHash(actor, "POST", route, json.readTree(body)), state, status))).isNull();
    }

    private void audit(JsonNode response, String actor, String action, String state) {
        String trace = response.path("traceId").asText();
        assertThat(trace).matches("[0-9a-f-]{36}");
        assertThat(audits.put(trace, new ExpectedAudit(actor, action, state))).isNull();
    }

    private void note(String stage) throws Exception {
        Files.writeString(manifest, json.writeValueAsString(Map.of("stage", stage, "run", run, "sqlAttempts", GUARD.snapshot(),
            "deletes", GUARD.deletes.get(), "lockingSelects", GUARD.locks.get(), "orderId", order == null ? "" : order.id(),
            "paymentId", payment == null ? "" : payment, "injectionReachedDriver", INJECTED.get())) + "\n", StandardOpenOption.APPEND);
    }

    private record Claim(String actor, String route, String requestHash, String state, String status) {}
    private record ExpectedAudit(String actor, String action, String state) {}
}
