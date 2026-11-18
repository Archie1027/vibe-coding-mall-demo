package com.zhicai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.trade.QuoteModels;
import com.zhicai.platform.trade.QuoteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Independent browser candidate; unapproved SQL is blocked before driver execution. */
@EnabledIfEnvironmentVariable(named="M1_CHECKOUT_BROWSER_INTEGRATION",matches="read-only-checkout-browser|approved-single-checkout-browser")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.flyway.enabled=false","spring.sql.init.mode=never","app.dev-bootstrap.enabled=false",
    "app.catalog.writes-enabled=false","app.customer.writes-enabled=true","app.order.writes-enabled=true","debug=false"})
@Import(MySqlCheckoutBrowserIntegrationTest.GuardConfiguration.class)
class MySqlCheckoutBrowserIntegrationTest {
    static final String URL="jdbc:mysql://127.0.0.1:3306/zhicai_dev?connectionTimeZone=UTC";
    static final Path BASELINE=Path.of(".local/acceptance");
    static final String BASELINE_SHA="1a5dce656abd803230ff17d27c8ae f17b767c760d594230f80bc615b05eb80df".replace(" ","");
    static final List<String> TABLES=List.of("account","role","permission","account_role","role_permission","warehouse","account_warehouse",
        "category","brand","asset","product","sku","product_asset","flyway_schema_history","customer_address","cart_item",
        "idempotency_request","audit_log","stock_balance","stock_reservation","stock_ledger","checkout_quote","sales_order","sales_order_item");
    static final List<String> OWNED=List.of("customer_address","cart_item","stock_balance","checkout_quote","sales_order","sales_order_item",
        "stock_reservation","stock_ledger","idempotency_request","audit_log");
    static final String REPLAY_KEY=Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
    static final MySqlAcceptanceSupport.SqlGuard GUARD=new MySqlAcceptanceSupport.SqlGuard(Map.ofEntries(
        Map.entry("INSERT customer_address",1),Map.entry("INSERT cart_item",1),Map.entry("INSERT stock_balance",1),Map.entry("INSERT checkout_quote",1),
        Map.entry("INSERT sales_order",1),Map.entry("INSERT sales_order_item",1),Map.entry("INSERT stock_reservation",1),
        Map.entry("INSERT stock_ledger",1),Map.entry("INSERT idempotency_request",2),Map.entry("INSERT audit_log",2),
        Map.entry("UPDATE stock_balance",1),Map.entry("UPDATE checkout_quote",1),Map.entry("UPDATE idempotency_request",2)
    ),OWNED,12,()->"approved-single-checkout-browser".equals(System.getenv("M1_CHECKOUT_BROWSER_INTEGRATION"))
        && System.getenv("M1_CHECKOUT_BROWSER_APPROVAL_ID")!=null && !System.getenv("M1_CHECKOUT_BROWSER_APPROVAL_ID").isBlank());
    @DynamicPropertySource static void local(DynamicPropertyRegistry properties){
        if(!URL.equals(System.getenv("DB_URL")))throw new IllegalStateException("Canonical local UTC JDBC URL required");
        properties.add("spring.datasource.url",()->URL);
        properties.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        properties.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");
        properties.add("spring.flyway.enabled",()->false);
        properties.add("spring.sql.init.mode",()->"never");
        properties.add("app.dev-bootstrap.enabled",()->false);
        properties.add("app.catalog.writes-enabled",()->false);
        properties.add("app.customer.writes-enabled",()->true);
        properties.add("app.order.writes-enabled",()->true);
        properties.add("app.catalog.replay-key",()->REPLAY_KEY);
    }
    @TestConfiguration static class GuardConfiguration {
        @Bean static BeanPostProcessor dataSourceGuard(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){
                return bean instanceof DataSource source?GUARD.dataSource(source):bean;
            }
        };}
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager manager;
    @Autowired QuoteService quotes;
    final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

    @Test @EnabledIfEnvironmentVariable(named="M1_CHECKOUT_BROWSER_INTEGRATION",matches="read-only-checkout-browser")
    void readOnlyBaselineAndSecurity() throws Exception {
        assertThat(GUARD.armed).isFalse();
        assertThat(GUARD.snapshot()).isEmpty();
        assertThat(GUARD.locks.get()).isZero();
        Path source=Path.of("src/test/java/com/zhicai/platform/MySqlCheckoutBrowserIntegrationTest.java");
        String fixtureHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        assertThat(fixtureHash).isEqualToIgnoringCase(System.getenv("M1_CHECKOUT_BROWSER_EXPECTED_FIXTURE_SHA256"));
        byte[] baselineBytes=Files.readAllBytes(BASELINE);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(baselineBytes));
        assertThat(hash).isEqualTo(BASELINE_SHA);
        JsonNode baseline=json.readTree(baselineBytes);
        assertThat(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)connection->connection.getMetaData().getURL())).isEqualTo(URL);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+00:00");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",String.class)).containsExactlyInAnyOrderElementsOf(TABLES);
        var history=jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        int[] checksums={-357524081,502337516,174647045,-493016158,237973485,947094408,-458546823,-652983552};
        assertThat(history).hasSize(8);
        for(int i=0;i<8;i++){
            assertThat(history.get(i).get("version").toString()).isEqualTo(Integer.toString(i+1));
            assertThat(((Number)history.get(i).get("checksum")).intValue()).isEqualTo(checksums[i]);
            assertThat(history.get(i).get("success").toString()).isIn("true","1");
        }
        for(String table:TABLES){
            int count=baseline.path("Counts").path(table).asInt(-1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class)).isEqualTo(count);
            var columns=jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table);
            assertThat(MySqlAcceptanceSupport.referenceDigest(jdbc,table,columns,count)).as("UTC full row digest: %s",table).isEqualTo(baseline.path("OriginalDigests").path(table).asText());
        }
        String password=System.getenv("M1_CHECKOUT_BROWSER_CUSTOMER_PASSWORD");
        assertThat(password).isNotBlank();
        var account=jdbc.queryForMap("SELECT CAST(id AS CHAR) AS id,password_hash FROM account WHERE account_type='CUSTOMER' AND username='customer' AND enabled=1");
        assertThat(passwords.matches(password,account.get("password_hash").toString())).isTrue();
        String invalid="{\"quoteId\":\"1\"}";
        assertThat(send("/api/v1/orders",invalid,null,UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
        String loginBody=json.writeValueAsString(Map.of("username","customer","password",password));
        var login=send("/api/v1/auth/customer/login",loginBody,null,null);
        assertThat(login.statusCode()).isEqualTo(200);
        String token=json.readTree(login.body()).path("data").path("accessToken").asText();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        String session="zhicai:auth:session:"+MySqlAcceptanceSupport.sha(token);
        assertThat(redis.hasKey(session)).isTrue();
        try {
            assertThat(send("/api/v1/orders",invalid,token,null).statusCode()).isEqualTo(400);
            assertThat(send("/api/v1/orders","{}",token,UUID.randomUUID().toString()).statusCode()).isEqualTo(400);
        } finally {
            assertThat(send("/api/v1/auth/logout",null,token,null).statusCode()).isEqualTo(200);
        }
        assertThat(redis.hasKey(session)).isFalse();
        assertThat(send("/api/v1/orders",invalid,token,UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
        assertThat(GUARD.snapshot()).isEmpty();
        assertThat(GUARD.locks.get()).isZero();
        runBrowser(null);
        System.out.println("CHECKOUT_BROWSER_READ_ONLY_OK baselineSha="+hash+" tables=24 mysqlWrites=0 lockingSelects=0 redisSessionRemoved=true");
    }
    @Test @EnabledIfEnvironmentVariable(named="M1_CHECKOUT_BROWSER_INTEGRATION",matches="approved-single-checkout-browser")
    void approvedSingleOrderHttp() throws Exception {
        // This method may only run after a new, source-bound approval. The full read-only proof runs before arming SQL.
        readOnlyBaselineAndSecurity();
        Path source=Path.of("src/test/java/com/zhicai/platform/MySqlCheckoutBrowserIntegrationTest.java");
        String fixtureHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        assertThat(fixtureHash).isEqualToIgnoringCase(System.getenv("M1_CHECKOUT_BROWSER_EXPECTED_FIXTURE_SHA256"));
        assertThat(System.getenv("M1_CHECKOUT_BROWSER_APPROVAL_ID")).matches("[A-Za-z0-9._:-]{8,100}");
        Path evidence=Path.of(Objects.requireNonNull(System.getenv("M1_CHECKOUT_BROWSER_EVIDENCE_ROOT"))).toAbsolutePath().normalize();
        assertThat(evidence.startsWith(Path.of("."))).isFalse();
        assertThat(Files.isDirectory(evidence)).isTrue();
        Path manifest=evidence.resolve("checkout-browser-"+UUID.randomUUID()+".jsonl");
        Files.createFile(manifest);
        var original=new LinkedHashMap<String,List<Map<String,String>>>();
        var columns=new LinkedHashMap<String,List<String>>();
        for(String table:TABLES){
            columns.put(table,jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table));
            original.put(table,MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),false));
        }
        var originalDigests=new LinkedHashMap<String,String>();
        original.forEach((table,rows)->originalDigests.put(table,MySqlAcceptanceSupport.digest(rows)));
        String run=UUID.randomUUID().toString();
        String recipient="订单HTTP验收"+run,detail="一次性地址"+run,remark="订单验收"+run;
        String owner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' AND username='customer' AND enabled=1",String.class);
        String warehouse=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM warehouse WHERE enabled=1 AND fulfillment_enabled=1 ORDER BY priority,id LIMIT 1",String.class);
        String sku=jdbc.queryForObject("SELECT CAST(s.id AS CHAR) FROM sku s JOIN product p ON p.id=s.product_id JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id WHERE s.enabled=1 AND p.state='ON_SALE' AND c.enabled=1 AND b.enabled=1 ORDER BY s.id LIMIT 1",String.class);
        note(manifest,Map.ofEntries(Map.entry("stage","write-intent"),Map.entry("fixtureSha256",fixtureHash),
            Map.entry("approvalId",System.getenv("M1_CHECKOUT_BROWSER_APPROVAL_ID")),Map.entry("sqlCaps",GUARD.caps),
            Map.entry("normalCommittedRows",12),Map.entry("deleteCap",12),Map.entry("baselineSha256",BASELINE_SHA),
            Map.entry("run",run),Map.entry("recipient",recipient),Map.entry("detail",detail),Map.entry("owner",owner),
            Map.entry("warehouse",warehouse),Map.entry("sku",sku)));
        String token=null,session=null;
        try {
            String password=Objects.requireNonNull(System.getenv("M1_CHECKOUT_BROWSER_CUSTOMER_PASSWORD"));
            var login=send("/api/v1/auth/customer/login",json.writeValueAsString(Map.of("username","customer","password",password)),null,null);
            assertThat(login.statusCode()).isEqualTo(200);
            token=json.readTree(login.body()).path("data").path("accessToken").asText();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            session="zhicai:auth:session:"+MySqlAcceptanceSupport.sha(token);
            assertThat(redis.hasKey(session)).isTrue();
            GUARD.armed=true;
            assertThat(jdbc.update("INSERT INTO customer_address(customer_id,recipient,phone,province,city,district,detail,is_default) VALUES(CAST(? AS UNSIGNED),?,'10000000007','测试省','测试市','测试区',?,FALSE)",owner,recipient,detail)).isEqualTo(1);
            String address=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM customer_address WHERE customer_id=CAST(? AS UNSIGNED) AND BINARY recipient=BINARY ? AND BINARY detail=BINARY ?",String.class,owner,recipient,detail);
            assertThat(jdbc.update("INSERT INTO stock_balance(warehouse_id,sku_id,on_hand,reserved,quarantine) VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),2,0,0)",warehouse,sku)).isEqualTo(1);
            String balance=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM stock_balance WHERE warehouse_id=CAST(? AS UNSIGNED) AND sku_id=CAST(? AS UNSIGNED)",String.class,warehouse,sku);
            note(manifest,Map.of("stage","seeds-created","addressId",address,"balanceId",balance,"owner",owner,"warehouse",warehouse,"sku",sku));
            assertThat(jdbc.update("INSERT INTO cart_item(customer_id,sku_id,quantity,selected) VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),1,TRUE)",owner,sku)).isEqualTo(1);
            var browser=runBrowser(evidence);
            JsonNode quote=browser.path("quote"),order=browser.path("order");
            String orderId=order.path("id").asText();
            assertThat(orderId).matches("[1-9][0-9]{0,18}");
            assertThat(order.path("customerId").asText()).isEqualTo(owner);
            assertThat(order.path("warehouseId").asText()).isEqualTo(warehouse);
            assertThat(order.path("items")).hasSize(1);
            assertThat(order.path("items").get(0).path("skuId").asText()).isEqualTo(sku);
            assertThat(order.path("payableMinor").asText()).isEqualTo(quote.path("payableMinor").asText());
            assertThat(Duration.between(Instant.parse(order.path("createdAt").asText()),Instant.parse(order.path("expiresAt").asText())).toSeconds()).isEqualTo(1800);
            assertThat(browser.path("quoteCount").asInt()).isEqualTo(1);
            assertThat(browser.path("orderAttempts")).hasSize(2);
            assertThat(browser.path("orderAttempts").get(0)).isEqualTo(browser.path("orderAttempts").get(1));
            assertThat(redis.hasKey(browser.path("sessionKey").asText())).isFalse();
            var frozen=new LinkedHashMap<String,List<Map<String,String>>>();
            for(String table:OWNED){
                var rows=MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),false);
                assertThat(rows).as("exactly one owned row: %s",table).hasSize(table.equals("idempotency_request")||table.equals("audit_log")?2:1);
                frozen.put(table,rows);
            }
            assertThat(frozen.get("customer_address").getFirst().get("id")).isEqualTo(address);
            assertThat(frozen.get("cart_item").getFirst().get("customer_id")).isEqualTo(owner);
            assertThat(frozen.get("cart_item").getFirst().get("sku_id")).isEqualTo(sku);
            assertThat(frozen.get("stock_balance").getFirst().get("id")).isEqualTo(balance);
            assertThat(frozen.get("stock_balance").getFirst().get("reserved")).isEqualTo("1");
            assertThat(frozen.get("checkout_quote").getFirst().get("id")).isEqualTo(quote.path("id").asText());
            assertThat(frozen.get("checkout_quote").getFirst().get("used_order_id")).isEqualTo(orderId);
            assertThat(frozen.get("sales_order").getFirst().get("id")).isEqualTo(orderId);
            assertThat(frozen.get("sales_order").getFirst().get("state")).isEqualTo("UNPAID");
            assertThat(frozen.get("sales_order_item").getFirst().get("order_id")).isEqualTo(orderId);
            assertThat(frozen.get("stock_reservation").getFirst().get("business_line_id")).isEqualTo(frozen.get("sales_order_item").getFirst().get("id"));
            assertThat(frozen.get("stock_reservation").getFirst().get("state")).isEqualTo("HELD");
            assertThat(frozen.get("stock_ledger").getFirst().get("biz_id")).isEqualTo(orderId);
            assertThat(frozen.get("stock_ledger").getFirst().get("action")).isEqualTo("RESERVE");
            for(var row:frozen.get("idempotency_request")) {
                assertThat(row.get("state")).isEqualTo("COMPLETED");
                assertThat(row.get("http_status")).isEqualTo("201");
                assertThat(row.get("actor_scope")).isEqualTo("CUSTOMER:"+owner);
            }
            assertThat(frozen.get("idempotency_request").stream().map(row->row.get("route")).toList()).containsExactlyInAnyOrder("/api/v1/checkout/quotes","/api/v1/orders");
            var orderClaim=frozen.get("idempotency_request").stream().filter(row->row.get("route").equals("/api/v1/orders")).findFirst().orElseThrow();
            assertThat(orderClaim.get("key_hash")).isEqualTo(browser.path("orderAttempts").get(0).path("keyHash").asText());
            assertThat(frozen.get("audit_log").stream().map(row->row.get("action")).toList()).containsExactlyInAnyOrder("QUOTE_CREATE","ORDER_CREATE");
            for(var row:frozen.get("audit_log"))assertThat(row.get("masked_before_after")).doesNotContain(recipient,detail,"10000000007");
            assertThat(GUARD.snapshot()).containsExactlyInAnyOrderEntriesOf(GUARD.caps);
            for(String table:TABLES)if(!OWNED.contains(table))assertThat(MySqlAcceptanceSupport.digest(MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),false))).isEqualTo(originalDigests.get(table));
            note(manifest,Map.of("stage","postimage-verified","rows",frozen,"sqlAttempts",GUARD.snapshot()));
            assertThat(send("/api/v1/auth/logout",null,token,null).statusCode()).isEqualTo(200);
            assertThat(redis.hasKey(session)).isFalse();
            var cleanup=new TransactionTemplate(manager);
            cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            cleanup.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            cleanup.setTimeout(30);
            cleanup.executeWithoutResult(status->{
                for(String table:OWNED)assertThat(MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),true)).isEqualTo(frozen.get(table));
                GUARD.cleanup=true;
                try {
                    for(String table:List.of("audit_log","idempotency_request","stock_ledger","stock_reservation","sales_order_item","sales_order","checkout_quote","stock_balance","cart_item","customer_address")){
                        for(var row:frozen.get(table)) assertThat(jdbc.update("DELETE FROM `"+table+"` WHERE id=CAST(? AS UNSIGNED) AND "+MySqlAcceptanceSupport.hashSql(columns.get(table))+"=?",row.get("id"),row.get("_guard_hash"))).isEqualTo(1);
                    }
                    for(String table:TABLES)assertThat(MySqlAcceptanceSupport.digest(MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),false))).isEqualTo(originalDigests.get(table));
                }finally{GUARD.cleanup=false;}
            });
            assertThat(GUARD.deletes.get()).isEqualTo(12);
            note(manifest,Map.of("stage","cleaned-and-verified","sqlAttempts",GUARD.snapshot(),"deleteAttempts",12,"originalDigests",originalDigests));
            System.out.println("CHECKOUT_BROWSER_WRITE_OK orderCreated=true replayed=true cleanup=12 baselineRestored=24");
        }catch(Exception|AssertionError failure){
            note(manifest,Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName(),"sqlAttempts",GUARD.snapshot(),"deleteAttempts",GUARD.deletes.get()));
            throw failure;
        }finally{GUARD.armed=false;GUARD.cleanup=false;}
    }
    private JsonNode runBrowser(Path parent) throws Exception {
        Path root=Path.of(Objects.requireNonNull(System.getenv("M1_CHECKOUT_BROWSER_EVIDENCE_ROOT"))).toRealPath();
        assertThat(root.startsWith(Path.of(".").toRealPath())).isFalse();
        Path directory=Files.createDirectory((parent==null?root:parent).resolve("browser-"+UUID.randomUUID()));
        Path source=Path.of(Objects.requireNonNull(System.getenv("M1_CHECKOUT_BROWSER_SCRIPT"))).toRealPath();
        byte[] bytes=Files.readAllBytes(source);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualToIgnoringCase(System.getenv("M1_CHECKOUT_BROWSER_SCRIPT_SHA256"));
        Path script=directory.resolve("browser.cjs"); Files.write(script,bytes,StandardOpenOption.CREATE_NEW);
        var builder=new ProcessBuilder(Objects.requireNonNull(System.getenv("M1_CHECKOUT_BROWSER_NODE")),script.toString());
        builder.directory(directory.toFile());builder.redirectErrorStream(true);builder.redirectOutput(directory.resolve("browser.log").toFile());
        var env=builder.environment();
        env.put("M1_CHECKOUT_BROWSER_MODE",GUARD.armed?"approved-single-checkout-browser":"preflight");
        env.put("M1_CHECKOUT_BROWSER_RUN",directory.toString());env.put("M1_CHECKOUT_BROWSER_API","http://127.0.0.1:"+port);
        env.put("M1_CHECKOUT_BROWSER_PASSWORD",Objects.requireNonNull(System.getenv("M1_CHECKOUT_BROWSER_CUSTOMER_PASSWORD")));
        Process child=builder.start();var descendants=new java.util.LinkedHashSet<ProcessHandle>();
        try {
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
            while(true){descendants.addAll(child.descendants().toList());if(child.waitFor(100,java.util.concurrent.TimeUnit.MILLISECONDS))break;
                if(System.nanoTime()>deadline)throw new IllegalStateException("Browser deadline; preserve SQL");}
            assertThat(child.exitValue()).as("See browser.log; no automatic rerun or cleanup").isZero();
        } finally {
            descendants.addAll(child.descendants().toList());
            for(var process:descendants)if(process.isAlive())process.destroyForcibly();
            if(child.isAlive())child.destroyForcibly();
            for(var process:descendants)process.onExit().get(10,java.util.concurrent.TimeUnit.SECONDS);
            child.onExit().get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        return json.readTree(Files.readString(directory.resolve("browser-result.json")));
    }
    private void note(Path path,Map<String,?> event) throws Exception {
        Files.writeString(path,json.writeValueAsString(event)+"\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
    }
    private HttpResponse<String> send(String route,String body,String token,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+route)).version(HttpClient.Version.HTTP_1_1)
            .timeout(Duration.ofSeconds(20)).header("Accept","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(key!=null)request.header("Idempotency-Key",key);
        if(body!=null)request.header("Content-Type","application/json");
        var result=http.send(request.POST(body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(result.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        return result;
    }
}
