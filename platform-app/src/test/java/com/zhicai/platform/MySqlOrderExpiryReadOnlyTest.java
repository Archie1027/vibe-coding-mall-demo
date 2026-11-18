package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.inventory.InventoryReservationService;
import com.zhicai.platform.inventory.StockMapper;
import com.zhicai.platform.scheduling.OrderExpiryConfiguration;
import com.zhicai.platform.trade.OrderClosingMapper;
import com.zhicai.platform.trade.OrderClosingService;
import com.zhicai.platform.trade.PaymentClosingCheck;
import com.zhicai.platform.trade.PaymentClosingMapper;
import java.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

/** Read-only mapper and wiring probe. No approval path or SQL write mode exists here. */
@EnabledIfEnvironmentVariable(named = "M1_EXPIRY_READ_ONLY", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.flyway.enabled=false", "spring.sql.init.mode=never", "app.dev-bootstrap.enabled=false",
    "app.catalog.writes-enabled=false", "app.customer.writes-enabled=false",
    "app.order.writes-enabled=false", "app.order.expiry-enabled=false" })
@Import(MySqlOrderExpiryReadOnlyTest.GuardConfiguration.class)
class MySqlOrderExpiryReadOnlyTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/zhicai_dev?connectionTimeZone=UTC";
    private static final Path BASELINE = Path.of(".local/acceptance");
    private static final String BASELINE_SHA = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final MySqlAcceptanceSupport.SqlGuard GUARD =
        new MySqlAcceptanceSupport.SqlGuard(Map.of(), List.of(), 0, () -> false);

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        if (!URL.equals(System.getenv("DB_URL"))) throw new IllegalStateException("Canonical local UTC target required");
        properties.add("spring.datasource.url", () -> URL);
        properties.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET time_zone = '+00:00'");
    }

    @TestConfiguration static class GuardConfiguration {
        @Bean static BeanPostProcessor guard() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource source ? GUARD.dataSource(source) : bean;
                }
            };
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Autowired OrderClosingMapper orders;
    @Autowired StockMapper stock;
    @Autowired PaymentClosingMapper payments;

    @Test @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    void realUnlockedCandidateQueryAndDisabledWiring() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("zhicai_dev");
        var bytes = Files.readAllBytes(BASELINE);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(BASELINE_SHA);
        var reference = new ObjectMapper().readTree(bytes);
        assertThat(reference.path("Database").asText()).isEqualTo("zhicai_dev");
        assertThat(reference.path("Rows").size()).isEqualTo(26);
        var tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'", String.class);
        assertThat(tables).hasSize(26);
        for (var row : reference.path("Rows")) {
            String table = row.path("table").asText();
            assertThat(table).matches("[a-z_]+");
            assertThat(tables).contains(table);
            var columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position", String.class, table);
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
            assertThat(count).isEqualTo(row.path("count").asInt());
            assertThat(MySqlAcceptanceSupport.referenceDigest(jdbc, table, columns, count)).isEqualTo(row.path("sha256").asText());
        }
        for (String table : List.of("customer_address", "stock_balance", "checkout_quote", "sales_order",
                "sales_order_item", "stock_reservation", "stock_ledger", "payment", "task_lease")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE account_type='CUSTOMER' AND username='customer' AND enabled=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE enabled=1 AND fulfillment_enabled=1", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku s JOIN product p ON p.id=s.product_id JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id WHERE s.enabled=1 AND p.state='ON_SALE' AND c.enabled=1 AND b.enabled=1", Integer.class)).isPositive();
        assertThat(context.getBeansOfType(OrderExpiryConfiguration.ExpirySchedule.class)).isEmpty();
        assertThat(context.getBeansOfType(OrderClosingService.class)).isEmpty();
        var closing = new OrderClosingService(orders, new InventoryReservationService(stock), new PaymentClosingCheck(payments), Clock.systemUTC());
        assertThat(closing.expiredCandidates(Clock.systemUTC().instant(), null, 100)).isEmpty();
        assertThat(GUARD.snapshot()).isEmpty();
        assertThat(GUARD.locks.get()).isZero();
    }
}
