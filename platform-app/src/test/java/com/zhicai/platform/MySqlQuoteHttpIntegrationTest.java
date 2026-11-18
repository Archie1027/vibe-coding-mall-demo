package com.zhicai.platform;

import com.fasterxml.jackson.databind.*;
import com.zhicai.platform.trade.*;
import com.zhicai.platform.catalog.CheckoutCatalogService;
import com.zhicai.platform.write.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.zhicai.platform.trade.QuoteModels.*;

/** Explicit opt-in candidate; any unknown outcome retains SQL/evidence. No cleanup lifecycle hook. */
@EnabledIfEnvironmentVariable(named="M1_QUOTE_HTTP_INTEGRATION",matches="read-only-quote-http-startup|approved-single-quote-http|read-only-retained|resume-retained")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.flyway.enabled=false","spring.sql.init.mode=never","app.dev-bootstrap.enabled=false",
    "app.catalog.writes-enabled=false","app.customer.writes-enabled=true","debug=false"})
@Import(MySqlQuoteHttpIntegrationTest.GuardConfiguration.class)
class MySqlQuoteHttpIntegrationTest {
    static final String CANONICAL="jdbc:mysql://127.0.0.1:3306/zhicai_dev?connectionTimeZone=UTC";
    static final String ROUTE="/api/v1/checkout/quotes";
    static final String OLD_RUN="d6088481-d858-4d1e-9a13-e1622bfb1f3e";
    static final String OLD_MANIFEST_SHA="0000000000000000000000000000000000000000000000000000000000000000";
    static final String OLD_QUOTE_JSON_SHA="0000000000000000000000000000000000000000000000000000000000000000";
    static final String OLD_CIPHER_SHA="0000000000000000000000000000000000000000000000000000000000000000";
    static final String OLD_SUCCESS_KEY_HASH="0000000000000000000000000000000000000000000000000000000000000000";
    static final String OLD_FAILED_KEY_HASH="0000000000000000000000000000000000000000000000000000000000000000";
    static final String REPLAY_KEY=Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32));
    static final List<String> TABLES=List.of("account","role","permission","account_role","role_permission","warehouse","account_warehouse",
        "category","brand","asset","product","sku","product_asset","flyway_schema_history","customer_address","cart_item",
        "idempotency_request","audit_log","stock_balance","stock_reservation","stock_ledger","checkout_quote");
    static final List<String> OWNED=List.of("customer_address","stock_balance","checkout_quote","idempotency_request","audit_log");
    static final List<String> EMPTY=List.of("customer_address","cart_item","idempotency_request","audit_log","stock_balance","stock_reservation","stock_ledger","checkout_quote");
    static final SqlGuard GUARD=new SqlGuard();
    @DynamicPropertySource static void localDatabase(DynamicPropertyRegistry p){
        if(!CANONICAL.equals(System.getenv("DB_URL")))throw new IllegalStateException("Canonical UTC JDBC target required");
        p.add("spring.datasource.url",()->CANONICAL);p.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        p.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");
        p.add("spring.flyway.enabled",()->false);p.add("spring.sql.init.mode",()->"never");
        p.add("app.dev-bootstrap.enabled",()->false);p.add("app.catalog.writes-enabled",()->false);
        p.add("app.customer.writes-enabled",()->true);p.add("app.catalog.replay-key",()->REPLAY_KEY);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @Autowired Environment environment;
    @Autowired QuoteApplication application;
    @Autowired QuoteService quoteService;
    @Autowired CheckoutCatalogService catalog;
    @Autowired AddressMapper addresses;
    final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    final String run=UUID.randomUUID().toString(),recipient="验收"+run,detail="临时HTTP报价验收-"+run;
    final Map<String,String> baseline=new LinkedHashMap<>();
    final List<Actor> actors=new ArrayList<>();
    final Map<String,Expected> expected=new LinkedHashMap<>();
    final Map<String,List<Map<String,String>>> verifiedResources=new LinkedHashMap<>();
    Path manifest;String owner,warehouse,sku,addressId,balanceId,quoteId;
    record Actor(String id,String token,String session) {}
    record Reply(int status,String body,JsonNode node,String trace) {JsonNode data(){return node.path("data");}}
    record Expected(Request request,Reply response) {}

    @Test @EnabledIfEnvironmentVariable(named="M1_QUOTE_HTTP_INTEGRATION",matches="read-only-retained")
    void readOnlyRetained() throws Exception {
        retainedPrepare();
        try {
            var old=oldManifest();var frozen=retainedRows(old);
            retainedGuardProof();
            var scene=retainedScene(old,frozen);byte[] bytes=json.writeValueAsBytes(scene);
            Path path=scenePath();physical(path.getParent());
            try {Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
            catch(FileAlreadyExistsException existing){assertThat(Files.readAllBytes(path)).isEqualTo(bytes);}
            assertThat(Files.readAllBytes(path)).isEqualTo(bytes);
            originalUnchanged(TABLES.stream().filter(t->!OWNED.contains(t)).toList());
            assertThat(GUARD.counts).isEmpty();assertThat(GUARD.blocked).isZero();
            note(Map.of("stage","read-only-retained-verified","oldManifestSha256",OLD_MANIFEST_SHA,"sceneSha256",shaBytes(bytes),"resourceDigests",digests(frozen),"available",5,"insufficientQuantity",6,"sqlWriteAttempts",0));
        }catch(Exception|AssertionError failure){retained(failure);throw failure;}
    }

    @Test @EnabledIfEnvironmentVariable(named="M1_QUOTE_HTTP_INTEGRATION",matches="resume-retained")
    void resumeRetained() throws Exception {
        retainedPrepare();
        try {
            var old=oldManifest();var frozen=retainedRows(old);var scene=retainedScene(old,frozen);
            byte[] bytes=Files.readAllBytes(scenePath());
            assertThat(shaBytes(bytes)).isEqualTo(System.getenv("M1_QUOTE_HTTP_SCENE_SHA256"));
            assertThat(json.readTree(bytes)).isEqualTo(json.valueToTree(scene));
            String absent="1844674407370955161";
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_address WHERE id=CAST(? AS UNSIGNED)",Integer.class,absent)).isZero();
            originalUnchanged(TABLES.stream().filter(t->!OWNED.contains(t)).toList());
            note(Map.of("stage","resume-prewrite-verified","sceneSha256",shaBytes(bytes),"oldManifestSha256",OLD_MANIFEST_SHA));
            Actor customer=login("customer","CUSTOMER","/api/v1/auth/customer/login","M1_QUOTE_HTTP_CUSTOMER_PASSWORD");
            assertThat(customer.id()).isEqualTo(owner);
            GUARD.phase="retained-business";
            Request insufficient=new Request(addressId,List.of(new Item(sku,6)),null);
            String key=UUID.randomUUID().toString();GUARD.allowRetainedHash(sha(key));Reply rejected=submit(insufficient,key);code(rejected,409,"NO_SINGLE_WAREHOUSE");replay(insufficient,key,rejected);
            Request missing=new Request(absent,List.of(new Item(sku,1)),null);
            key=UUID.randomUUID().toString();GUARD.allowRetainedHash(sha(key));Reply absentReply=submit(missing,key);code(absentReply,404,"NOT_FOUND");replay(missing,key,absentReply);
            assertThat(GUARD.counts).containsExactlyInAnyOrderEntriesOf(Map.of("INSERT idempotency_request",2,"UPDATE idempotency_request",2));
            assertThat(GUARD.blocked).isZero();GUARD.phase="closed";
            var all=snapshot();assertOldRows(all,frozen);assertThat(all.get("idempotency_request")).hasSize(4);
            var cipher=new ReplayCipher(REPLAY_KEY);var fingerprint=new WriteRequestFingerprint(json);
            for(var entry:expected.entrySet()){
                var matched=all.get("idempotency_request").stream().filter(row->sha(entry.getKey()).equals(row.get("key_hash"))).toList();assertThat(matched).hasSize(1);
                var row=matched.getFirst();var e=entry.getValue();
                assertThat(row.get("actor_scope")).isEqualTo("CUSTOMER:"+owner);assertThat(row.get("method")).isEqualTo("POST");assertThat(row.get("route")).isEqualTo(ROUTE);
                assertThat(row.get("request_hash")).isEqualTo(fingerprint.requestHash("CUSTOMER:"+owner,"POST",ROUTE,json.valueToTree(e.request())));
                assertThat(row.get("state")).isEqualTo("COMPLETED");assertThat(Integer.parseInt(row.get("http_status"))).isEqualTo(e.response().status());
                String plaintext=cipher.decrypt(HexFormat.of().parseHex(row.get("encrypted_response")),row.get("id")+":"+row.get("owner_token")+":"+row.get("request_hash"));
                assertThat(Arrays.equals(plaintext.getBytes(StandardCharsets.UTF_8),e.response().body().getBytes(StandardCharsets.UTF_8))).isTrue();
            }
            originalUnchanged(TABLES.stream().filter(t->!OWNED.contains(t)).toList());
            logout();assertOldRows(snapshot(),frozen);
            note(Map.of("stage","retained-checks-passed","resourceDigests",digests(all),"sqlAttempts",new TreeMap<>(GUARD.counts)));
            cleanup(all);
            originalUnchanged(TABLES);for(String table:EMPTY)assertThat(count(table)).isZero();
            note(Map.of("stage","cleaned-and-verified","originalDigests",baseline,"sqlAttempts",new TreeMap<>(GUARD.counts),"autoIncrementRestored",false));
        }catch(Exception|AssertionError failure){retained(failure);throw failure;}
        finally{GUARD.phase="closed";}
    }

    @Test @EnabledIfEnvironmentVariable(named="M1_QUOTE_HTTP_INTEGRATION",matches="read-only-quote-http-startup")
    void readOnlyStartupAndBaseline() throws Exception {
        prepare();
        try {
            frozenDriftGuardCheck();authenticationAndValidation();logout();originalUnchanged(TABLES);
            assertThat(GUARD.counts).isEmpty();assertThat(GUARD.blocked).isZero();
            note(Map.of("stage","read-only-startup-verified","sqlWriteAttempts",0,"blockedSqlWrites",0,"originalDigests",baseline,"redisSessionsRemoved",true));
        }catch(Exception|AssertionError failure){retained(failure);throw failure;}
    }
    @Test @EnabledIfEnvironmentVariable(named="M1_QUOTE_HTTP_INTEGRATION",matches="approved-single-quote-http")
    void approvedSingleQuoteHttpScenario() throws Exception {
        prepare();
        try {
            authenticationAndValidation();
            GUARD.phase="seed";
            assertThat(jdbc.update("INSERT INTO customer_address(customer_id,recipient,phone,province,city,district,detail,is_default) VALUES(CAST(? AS UNSIGNED),?,'10000000007','测试省','测试市','测试区',?,FALSE)",owner,recipient,detail)).isEqualTo(1);
            addressId=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM customer_address WHERE customer_id=CAST(? AS UNSIGNED) AND BINARY recipient=BINARY ? AND BINARY detail=BINARY ?",String.class,owner,recipient,detail);
            assertThat(jdbc.update("INSERT INTO stock_balance(warehouse_id,sku_id,on_hand,reserved,quarantine) VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),5,0,2)",warehouse,sku)).isEqualTo(1);
            balanceId=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM stock_balance WHERE warehouse_id=CAST(? AS UNSIGNED) AND sku_id=CAST(? AS UNSIGNED)",String.class,warehouse,sku);
            var seed=snapshot();verifiedResources.put("customer_address",seed.get("customer_address"));verifiedResources.put("stock_balance",seed.get("stock_balance"));
            note(Map.of("stage","seed-created","addressId",addressId,"balanceId",balanceId,"owner",owner,"warehouse",warehouse,"sku",sku));
            GUARD.phase="business";
            var request=new Request(addressId,List.of(new Item(sku,1),new Item(sku,1)),null);
            String key=UUID.randomUUID().toString();Reply success=submit(request,key);assertThat(success.status()).isEqualTo(201);
            quoteId=success.data().path("id").asText();assertThat(success.data().path("id").isTextual()).isTrue();assertThat(quoteId).matches("[1-9][0-9]{0,18}");
            verifyQuote(success,seed);
            replay(request,key,success);code(submit(new Request(addressId,List.of(new Item(sku,1)),null),key),409,"IDEMPOTENCY_CONFLICT");
            Request insufficient=new Request(addressId,List.of(new Item(sku,4)),null);
            key=UUID.randomUUID().toString();Reply rejected=submit(insufficient,key);code(rejected,409,"NO_SINGLE_WAREHOUSE");replay(insufficient,key,rejected);
            Request absent=new Request("1844674407370955161",List.of(new Item(sku,1)),null);
            key=UUID.randomUUID().toString();Reply missing=submit(absent,key);code(missing,404,"NOT_FOUND");replay(absent,key,missing);
            verifyClaimsAndAudit(success);
            assertThat(rows("customer_address",false)).isEqualTo(seed.get("customer_address"));
            assertThat(rows("stock_balance",false)).isEqualTo(seed.get("stock_balance"));
            originalUnchanged(TABLES.stream().filter(t->!OWNED.contains(t)).toList());
            assertThat(GUARD.counts).containsExactlyInAnyOrderEntriesOf(Map.of("INSERT customer_address",1,"INSERT stock_balance",1,"INSERT checkout_quote",1,"INSERT idempotency_request",3,"UPDATE idempotency_request",3,"INSERT audit_log",1));
            assertThat(GUARD.blocked).isZero();GUARD.phase="closed";logout();
            var frozen=snapshot();validateOwnership(frozen,success);
            note(Map.of("stage","checks-passed","resourceDigests",digests(frozen),"sqlAttempts",new TreeMap<>(GUARD.counts)));
            cleanup(frozen);
            originalUnchanged(TABLES);for(String table:EMPTY)assertThat(count(table)).isZero();
            note(Map.of("stage","cleaned-and-verified","originalDigests",baseline,"sqlAttempts",new TreeMap<>(GUARD.counts),"autoIncrementRestored",false));
        }catch(Exception|AssertionError failure){retained(failure);throw failure;}
        finally{GUARD.phase="closed";}
    }
    private void prepare() throws Exception {
        GUARD.phase="closed";GUARD.counts.clear();GUARD.blocked=0;GUARD.retainedHashes.clear();
        String root=System.getenv("M1_QUOTE_HTTP_EVIDENCE_ROOT");assertThat(root).isNotBlank();Path directory=Path.of(root).toAbsolutePath().normalize();
        assertThat(directory.startsWith(Path.of("."))).isFalse();
        physical(directory);Files.createDirectories(directory);physical(directory);
        manifest=directory.resolve("quote-http-"+run+".jsonl");Files.createFile(manifest);
        note(Map.of("stage","baseline-intent","run",run,"fixtureSha256",System.getenv("M1_QUOTE_HTTP_FIXTURE_SHA256"),"implementationSha256",System.getenv("M1_QUOTE_HTTP_IMPLEMENTATION_SHA256"),"maxSeeds",2,"maxQuoteInserts",1,"maxClaimInserts",4,"maxClaimUpdates",4,"maxAuditInserts",2));
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("never");
        assertThat(environment.getProperty("app.dev-bootstrap.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("app.catalog.writes-enabled")).isEqualTo("false");
        assertThat(environment.getProperty("app.customer.writes-enabled")).isEqualTo("true");
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(quoteService)).isTrue();
        assertThat(jdbc.getDataSource()).isNotNull();assertThat(Proxy.isProxyClass(jdbc.getDataSource().getClass())).isTrue();
        verifyDatabaseStructure();for(String table:EMPTY)assertThat(count(table)).isZero();
        owner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' AND username='customer' AND enabled=1",String.class);
        warehouse=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM warehouse WHERE enabled=1 AND fulfillment_enabled=1 ORDER BY priority,id LIMIT 1",String.class);
        sku=jdbc.queryForObject("SELECT CAST(s.id AS CHAR) FROM sku s JOIN product p ON p.id=s.product_id JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id WHERE s.enabled=1 AND p.state='ON_SALE' AND c.enabled=1 AND b.enabled=1 ORDER BY s.id LIMIT 1",String.class);
        for(String table:TABLES)baseline.put(table,digest(rows(table,false)));
        note(Map.of("stage","baseline-verified","originalDigests",baseline,"tableCount",22,"writeGuard","closed"));
    }
    private void retainedPrepare() throws Exception {
        GUARD.phase="closed";GUARD.counts.clear();GUARD.blocked=0;GUARD.retainedHashes.clear();
        String root=System.getenv("M1_QUOTE_HTTP_EVIDENCE_ROOT");assertThat(root).isNotBlank();
        Path directory=Path.of(root).toAbsolutePath().normalize();assertThat(directory.startsWith(Path.of("."))).isFalse();
        physical(directory);Files.createDirectories(directory);physical(directory);
        manifest=directory.resolve("quote-http-retained-"+run+".jsonl");Files.createFile(manifest);
        note(Map.of("stage","retained-intent","oldManifestSha256",OLD_MANIFEST_SHA,"fixtureSha256",System.getenv("M1_QUOTE_HTTP_FIXTURE_SHA256"),"implementationSha256",System.getenv("M1_QUOTE_HTTP_IMPLEMENTATION_SHA256")));
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("never");
        assertThat(environment.getProperty("app.dev-bootstrap.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("app.catalog.writes-enabled")).isEqualTo("false");
        assertThat(environment.getProperty("app.customer.writes-enabled")).isEqualTo("true");
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(quoteService)).isTrue();
        assertThat(Proxy.isProxyClass(jdbc.getDataSource().getClass())).isTrue();
        verifyDatabaseStructure();
        owner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' AND username='customer' AND enabled=1",String.class);
        warehouse=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM warehouse WHERE enabled=1 AND fulfillment_enabled=1 ORDER BY priority,id LIMIT 1",String.class);
        sku=jdbc.queryForObject("SELECT CAST(s.id AS CHAR) FROM sku s JOIN product p ON p.id=s.product_id JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id WHERE s.enabled=1 AND p.state='ON_SALE' AND c.enabled=1 AND b.enabled=1 ORDER BY s.id LIMIT 1",String.class);
    }
    private Path oldManifestPath(){return Path.of(".local/acceptance"+OLD_RUN+".jsonl");}
    private Path scenePath(){return Path.of(".local/acceptance");}
    private List<JsonNode> oldManifest() throws Exception {
        Path path=oldManifestPath();physical(path.getParent());assertThat(shaBytes(Files.readAllBytes(path))).isEqualTo(OLD_MANIFEST_SHA);
        var events=new ArrayList<JsonNode>();for(String line:Files.readAllLines(path,StandardCharsets.UTF_8))events.add(json.readTree(line));
        assertThat(events.getFirst().path("stage").asText()).isEqualTo("baseline-intent");
        assertThat(events.getFirst().path("run").asText()).isEqualTo(OLD_RUN);
        assertThat(events.getLast().path("stage").asText()).isEqualTo("retained-after-failure");
        assertThat(events.getLast().path("blockedSqlWrites").asInt()).isEqualTo(1);
        JsonNode counts=events.getLast().path("sqlAttempts");
        for(var pair:Map.of("INSERT customer_address",1,"INSERT stock_balance",1,"INSERT checkout_quote",1,"INSERT idempotency_request",2,"UPDATE idempotency_request",1,"INSERT audit_log",1).entrySet())assertThat(counts.path(pair.getKey()).asInt()).isEqualTo(pair.getValue());
        return events;
    }
    private Map<String,List<Map<String,String>>> retainedRows(List<JsonNode> old) throws Exception {
        JsonNode seed=old.stream().filter(e->e.path("stage").asText().equals("seed-created")).findFirst().orElseThrow();
        addressId=seed.path("addressId").asText();balanceId=seed.path("balanceId").asText();
        assertThat(owner).isEqualTo("4");assertThat(warehouse).isEqualTo("1");assertThat(sku).isEqualTo("3001");
        assertThat(addressId).isEqualTo("24");assertThat(balanceId).isEqualTo("5");
        JsonNode original=old.stream().filter(e->e.path("stage").asText().equals("baseline-verified")).findFirst().orElseThrow().path("originalDigests");
        for(String table:TABLES)baseline.put(table,original.path(table).asText());
        originalUnchanged(TABLES.stream().filter(t->!OWNED.contains(t)).toList());
        var frozen=snapshot();
        for(String table:OWNED)assertThat(frozen.get(table)).hasSize(table.equals("idempotency_request")?2:1);
        var a=frozen.get("customer_address").getFirst();
        assertThat(a.get("id")).isEqualTo(addressId);assertThat(a.get("customer_id")).isEqualTo(owner);
        assertThat(a.get("recipient")).isEqualTo("验收"+OLD_RUN);assertThat(a.get("detail")).isEqualTo("临时HTTP报价验收-"+OLD_RUN);
        assertThat(a.get("phone")).isEqualTo("10000000007");assertThat(a.get("province")).isEqualTo("测试省");assertThat(a.get("city")).isEqualTo("测试市");assertThat(a.get("district")).isEqualTo("测试区");
        assertThat(a.get("is_default")).isIn("0","false");assertThat(a.get("version")).isEqualTo("0");
        var b=frozen.get("stock_balance").getFirst();assertThat(b.get("id")).isEqualTo(balanceId);
        assertThat(b.get("warehouse_id")).isEqualTo(warehouse);assertThat(b.get("sku_id")).isEqualTo(sku);
        assertThat(b.get("on_hand")).isEqualTo("5");assertThat(b.get("reserved")).isEqualTo("0");
        assertThat(b.get("quarantine")).isEqualTo("2");assertThat(b.get("version")).isEqualTo("0");
        assertThat(Long.parseLong(b.get("on_hand"))-Long.parseLong(b.get("reserved"))).isEqualTo(5);
        assertThat(6).isGreaterThan(5);
        var q=frozen.get("checkout_quote").getFirst();quoteId=q.get("id");assertThat(quoteId).isEqualTo("3");
        assertThat(q.get("customer_id")).isEqualTo(owner);assertThat(q.get("warehouse_id")).isEqualTo(warehouse);
        assertThat(q.get("used_order_id")).isNull();assertThat(sha(q.get("snapshot_json"))).isEqualTo(OLD_QUOTE_JSON_SHA);
        Instant created=Timestamp.valueOf(q.get("created_at")).toLocalDateTime().toInstant(ZoneOffset.UTC);
        Instant expires=Timestamp.valueOf(q.get("expires_at")).toLocalDateTime().toInstant(ZoneOffset.UTC);
        assertThat(created).isEqualTo(Instant.parse("2026-10-25T14:41:11.929Z"));assertThat(expires).isEqualTo(created.plusSeconds(300));
        JsonNode snapshot=json.readTree(q.get("snapshot_json"));assertThat(snapshot.path("owner").asText()).isEqualTo(owner);
        assertThat(snapshot.path("addressId").asText()).isEqualTo(addressId);assertThat(snapshot.path("warehouseId").asText()).isEqualTo(warehouse);
        var claims=frozen.get("idempotency_request");
        for(var row:claims){assertThat(row.get("actor_scope")).isEqualTo("CUSTOMER:"+owner);assertThat(row.get("method")).isEqualTo("POST");assertThat(row.get("route")).isEqualTo(ROUTE);}
        var successful=claims.stream().filter(r->r.get("id").equals("133")).findFirst().orElseThrow();
        assertThat(successful.get("key_hash")).isEqualTo(OLD_SUCCESS_KEY_HASH);assertThat(successful.get("state")).isEqualTo("COMPLETED");
        assertThat(successful.get("http_status")).isEqualTo("201");
        var fingerprint=new WriteRequestFingerprint(json);
        assertThat(successful.get("request_hash")).isEqualTo(fingerprint.requestHash("CUSTOMER:"+owner,"POST",ROUTE,json.valueToTree(new Request(addressId,List.of(new Item(sku,1),new Item(sku,1)),null))));
        assertThat(shaBytes(HexFormat.of().parseHex(successful.get("encrypted_response")))).isEqualTo(OLD_CIPHER_SHA);
        var failed=claims.stream().filter(r->r.get("id").equals("134")).findFirst().orElseThrow();
        assertThat(failed.get("key_hash")).isEqualTo(OLD_FAILED_KEY_HASH);assertThat(failed.get("state")).isEqualTo("IN_PROGRESS");
        assertThat(failed.get("request_hash")).isEqualTo(fingerprint.requestHash("CUSTOMER:"+owner,"POST",ROUTE,json.valueToTree(new Request(addressId,List.of(new Item(sku,4)),null))));
        var audit=frozen.get("audit_log").getFirst();assertThat(audit.get("id")).isEqualTo("110");
        assertThat(audit.get("actor_id")).isEqualTo(owner);assertThat(audit.get("action")).isEqualTo("QUOTE_CREATE");
        assertThat(audit.get("resource_type")).isEqualTo("QUOTE");assertThat(audit.get("resource_id")).isEqualTo(quoteId);
        assertThat(audit.get("result")).isEqualTo("SUCCESS");assertThat(audit.get("trace_id")).isEqualTo("3dae6f15-d0fa-4602-b835-0fc433d3a5c1");
        assertThat(json.readTree(audit.get("masked_before_after"))).isEqualTo(json.readTree("{\"before\":null,\"after\":{\"id\":\"3\"}}"));
        return frozen;
    }
    private Map<String,Object> retainedScene(List<JsonNode> old,Map<String,List<Map<String,String>>> frozen){
        var scene=new LinkedHashMap<String,Object>();scene.put("schema",1);scene.put("oldRun",OLD_RUN);
        scene.put("oldManifestSha256",OLD_MANIFEST_SHA);scene.put("originalDigests",baseline);
        scene.put("oldRows",frozen);scene.put("oldRowDigests",digests(frozen));return scene;
    }
    private void retainedGuardProof(){
        var proof=new SqlGuard("resume-retained");proof.phase="retained-business";
        String newHash=sha("retained-guard-proof");proof.allowRetainedHash(newHash);
        String token="00000000-0000-4000-8000-000000000001";
        var completion=new HashMap<Integer,Object>(Map.of(1,409,2,"cipher",3,"time",4,"time",5,"135",6,token));
        for(String oldId:List.of("133","134")){
            completion.put(5,oldId);
            assertThatThrownBy(()->proof.check(SqlGuard.RETAINED_COMPLETE,completion)).isInstanceOf(IllegalStateException.class);
        }
        completion.put(5,"135");
        assertThatThrownBy(()->proof.check("UPDATE idempotency_request SET request_hash=? WHERE id=CAST(? AS UNSIGNED)",completion)).isInstanceOf(IllegalStateException.class);
        var oldInsert=Map.<Integer,Object>of(1,"CUSTOMER:4",2,"POST",3,ROUTE,4,OLD_FAILED_KEY_HASH,6,token);
        assertThatThrownBy(()->proof.check(SqlGuard.RETAINED_INSERT,oldInsert)).isInstanceOf(IllegalStateException.class);
        var wrongToken=Map.<Integer,Object>of(1,"CUSTOMER:4",2,"POST",3,ROUTE,4,newHash,6,"4");
        assertThatThrownBy(()->proof.check(SqlGuard.RETAINED_INSERT,wrongToken)).isInstanceOf(IllegalStateException.class);
        proof.check(SqlGuard.RETAINED_INSERT,Map.of(1,"CUSTOMER:4",2,"POST",3,ROUTE,4,newHash,6,token));
        proof.check(SqlGuard.RETAINED_COMPLETE,completion);
        assertThat(proof.counts.get("INSERT idempotency_request")).isEqualTo(1);
        assertThat(proof.counts.get("UPDATE idempotency_request")).isEqualTo(1);
    }
    private void assertOldRows(Map<String,List<Map<String,String>>> actual,Map<String,List<Map<String,String>>> old){
        for(String table:OWNED){
            var originals=old.get(table);var matches=actual.get(table).stream().filter(row->originals.stream().anyMatch(o->o.get("id").equals(row.get("id")))).toList();
            assertThat(matches).isEqualTo(originals);
            if(!table.equals("idempotency_request"))assertThat(actual.get(table)).isEqualTo(originals);
        }
    }
    private void authenticationAndValidation() throws Exception {
        Actor customer=login("customer","CUSTOMER","/api/v1/auth/customer/login","M1_QUOTE_HTTP_CUSTOMER_PASSWORD");
        Actor staff=login("admin","STAFF","/api/v1/auth/staff/login","M1_QUOTE_HTTP_ADMIN_PASSWORD");
        assertThat(customer.id()).isEqualTo(owner);
        String body=json.writeValueAsString(new Request("1",List.of(new Item(sku,1)),null));
        assertThat(send(ROUTE,body,null,UUID.randomUUID().toString()).status()).isEqualTo(401);
        assertThat(send(ROUTE,body,staff.token(),UUID.randomUUID().toString()).status()).isEqualTo(403);
        assertThat(send(ROUTE,body,customer.token(),null).status()).isEqualTo(400);
        assertThat(send(ROUTE,"{\"addressId\":\"1\",\"items\":[]}",customer.token(),UUID.randomUUID().toString()).status()).isEqualTo(400);
        originalUnchanged(TABLES);assertThat(GUARD.counts).isEmpty();
    }
    private Actor login(String username,String type,String route,String variable) throws Exception {
        String secret=System.getenv(variable);if(secret==null||secret.isBlank())throw new IllegalStateException("Required private local login credential missing");
        var account=jdbc.queryForMap("SELECT CAST(id AS CHAR) id,password_hash FROM account WHERE username=? AND account_type=? AND enabled=1",username,type);
        // Boolean assertion only: never pass password/hash/token/body to assertion diagnostics.
        assertThat(passwords.matches(secret,account.get("password_hash").toString())).isTrue();
        Reply reply=send(route,json.writeValueAsString(Map.of("username",username,"password",secret)),null,null);
        assertThat(reply.status()).isEqualTo(200);String token=reply.data().path("accessToken").asText();
        assertThat(token.matches("[A-Za-z0-9_-]{43}")).isTrue();String session="zhicai:auth:session:"+sha(token);
        assertThat(redis.hasKey(session)).isTrue();Actor actor=new Actor(account.get("id").toString(),token,session);actors.add(actor);return actor;
    }
    private void logout() throws Exception {
        for(Actor actor:actors){assertThat(send("/api/v1/auth/logout",null,actor.token(),null).status()).isEqualTo(200);assertThat(redis.hasKey(actor.session())).isFalse();
            assertThat(send(ROUTE,json.writeValueAsString(new Request("1",List.of(new Item(sku,1)),null)),actor.token(),UUID.randomUUID().toString()).status()).isEqualTo(401);}
    }
    private Reply submit(Request request,String key) throws Exception {
        Reply result=send(ROUTE,json.writeValueAsString(request),actors.getFirst().token(),key);
        if(!expected.containsKey(key))expected.put(key,new Expected(request,result));return result;
    }
    private void replay(Request request,String key,Reply original) throws Exception {
        var before=snapshot();Reply result=submit(request,key);assertThat(result.status()).isEqualTo(original.status());
        assertThat(Arrays.equals(result.body().getBytes(StandardCharsets.UTF_8),original.body().getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(snapshot()).isEqualTo(before);
    }
    private Reply send(String route,String body,String token,String key) throws Exception {
        String requestId=UUID.randomUUID().toString();note(Map.of("stage","http-intent","requestId",requestId,"route",route,"keyHash",key==null?"none":sha(key)));
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+route)).version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(20)).header("Accept","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);if(key!=null)builder.header("Idempotency-Key",key);
        if(body!=null)builder.header("Content-Type","application/json");
        var response=http.send(builder.POST(body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        String trace=response.headers().firstValue("X-Trace-Id").orElse("");
        note(Map.of("stage","http-response","requestId",requestId,"status",response.statusCode(),"traceId",trace,"responseSha256",sha(response.body())));
        if(response.statusCode()>=500)throw new IllegalStateException("Unknown HTTP outcome; retain state without retry");
        JsonNode node=json.readTree(response.body());if(node==null||!node.isObject())throw new IllegalStateException("Unknown response shape; retain state");
        assertThat(trace).matches("[0-9a-fA-F-]{36}");return new Reply(response.statusCode(),response.body(),node,trace);
    }
    private void code(Reply reply,int status,String code){assertThat(reply.status()).isEqualTo(status);assertThat(reply.node().path("code").asText()).isEqualTo(code);}
    private void verifyQuote(Reply reply,Map<String,List<Map<String,String>>> seed) throws Exception {
        var transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var saved=transaction.execute(status->rows("checkout_quote",false));assertThat(saved).hasSize(1);var row=saved.getFirst();
        assertThat(row.get("id")).isEqualTo(quoteId);assertThat(row.get("customer_id")).isEqualTo(owner);assertThat(row.get("warehouse_id")).isEqualTo(warehouse);assertThat(row.get("used_order_id")).isNull();
        Instant created=Timestamp.valueOf(row.get("created_at")).toLocalDateTime().toInstant(ZoneOffset.UTC);
        Instant expires=Timestamp.valueOf(row.get("expires_at")).toLocalDateTime().toInstant(ZoneOffset.UTC);
        assertThat(created.getNano()%1_000_000).isZero();assertThat(expires).isEqualTo(created.plusSeconds(300));
        Quote quote=json.treeToValue(reply.data(),Quote.class);var current=catalog.skus(List.of(sku)).get(sku);assertThat(current).isNotNull();
        assertThat(quote.items()).hasSize(1);var line=quote.items().getFirst();assertThat(line.quantity()).isEqualTo(2);
        String amount=Long.toString(Math.multiplyExact(Long.parseLong(current.priceMinor()),2));
        assertThat(line).isEqualTo(new Line(sku,current.productName(),current.specs(),2,current.priceMinor(),amount,"0",amount));
        assertThat(quote.warehouseId()).isEqualTo(warehouse);assertThat(quote.grossMinor()).isEqualTo(amount);assertThat(quote.discountMinor()).isEqualTo("0");assertThat(quote.shippingMinor()).isEqualTo("0");assertThat(quote.payableMinor()).isEqualTo(amount);assertThat(quote.couponId()).isNull();assertThat(quote.expiresAt()).isEqualTo(expires);
        for(String field:List.of("id","warehouseId","grossMinor","discountMinor","shippingMinor","payableMinor"))assertThat(reply.data().path(field).isTextual()).isTrue();
        assertThat(reply.data().at("/items/0/unitPriceMinor").isTextual()).isTrue();assertThat(reply.data().at("/items/0/skuId").isTextual()).isTrue();
        var expectedSnapshot=new Snapshot(1,owner,addressId,addresses.find(owner,addressId),warehouse,List.of(new SnapshotLine(current,line)),amount,"0","0",amount,null,created,expires);
        assertThat(json.readTree(row.get("snapshot_json"))).isEqualTo(json.readTree(json.writeValueAsString(expectedSnapshot)));
        assertThat(rows("customer_address",false)).isEqualTo(seed.get("customer_address"));
        verifiedResources.put("checkout_quote",saved);
    }
    private void verifyClaimsAndAudit(Reply success) throws Exception {
        var claims=rows("idempotency_request",false);assertThat(claims).hasSize(3);assertThat(expected).hasSize(3);
        var fingerprint=new WriteRequestFingerprint(json);var cipher=new ReplayCipher(REPLAY_KEY);
        for(var row:claims){
            assertThat(row.get("actor_scope")).isEqualTo("CUSTOMER:"+owner);assertThat(row.get("method")).isEqualTo("POST");assertThat(row.get("route")).isEqualTo(ROUTE);assertThat(row.get("state")).isEqualTo("COMPLETED");
            var entry=expected.entrySet().stream().filter(e->sha(e.getKey()).equals(row.get("key_hash"))).findFirst().orElseThrow();var e=entry.getValue();
            assertThat(row.get("request_hash")).isEqualTo(fingerprint.requestHash("CUSTOMER:"+owner,"POST",ROUTE,json.valueToTree(e.request())));
            assertThat(Integer.parseInt(row.get("http_status"))).isEqualTo(e.response().status());
            String plaintext=cipher.decrypt(HexFormat.of().parseHex(row.get("encrypted_response")),row.get("id")+":"+row.get("owner_token")+":"+row.get("request_hash"));
            assertThat(Arrays.equals(plaintext.getBytes(StandardCharsets.UTF_8),e.response().body().getBytes(StandardCharsets.UTF_8))).isTrue();
        }
        var audits=rows("audit_log",false);assertThat(audits).hasSize(1);var audit=audits.getFirst();
        assertThat(audit.get("actor_id")).isEqualTo(owner);assertThat(audit.get("action")).isEqualTo("QUOTE_CREATE");assertThat(audit.get("resource_type")).isEqualTo("QUOTE");assertThat(audit.get("resource_id")).isEqualTo(quoteId);assertThat(audit.get("result")).isEqualTo("SUCCESS");assertThat(audit.get("trace_id")).isEqualTo(success.trace());
        assertThat(json.readTree(audit.get("masked_before_after"))).isEqualTo(json.readTree("{\"before\":null,\"after\":{\"id\":\""+quoteId+"\"}}"));
        verifiedResources.put("idempotency_request",claims);verifiedResources.put("audit_log",audits);
    }
    private void validateOwnership(Map<String,List<Map<String,String>>> frozen,Reply success) throws Exception {
        assertFrozenMatchesVerified(frozen,verifiedResources);
        assertThat(frozen.get("customer_address")).hasSize(1);assertThat(frozen.get("stock_balance")).hasSize(1);assertThat(frozen.get("checkout_quote")).hasSize(1);
        var a=frozen.get("customer_address").getFirst();assertThat(a.get("id")).isEqualTo(addressId);assertThat(a.get("customer_id")).isEqualTo(owner);assertThat(a.get("recipient")).isEqualTo(recipient);assertThat(a.get("detail")).isEqualTo(detail);assertThat(a.get("phone")).isEqualTo("10000000007");assertThat(a.get("province")).isEqualTo("测试省");assertThat(a.get("city")).isEqualTo("测试市");assertThat(a.get("district")).isEqualTo("测试区");assertThat(a.get("is_default")).isIn("0","false");assertThat(a.get("version")).isEqualTo("0");
        var b=frozen.get("stock_balance").getFirst();assertThat(b.get("id")).isEqualTo(balanceId);assertThat(b.get("warehouse_id")).isEqualTo(warehouse);assertThat(b.get("sku_id")).isEqualTo(sku);assertThat(b.get("on_hand")).isEqualTo("5");assertThat(b.get("reserved")).isEqualTo("0");assertThat(b.get("quarantine")).isEqualTo("2");assertThat(b.get("version")).isEqualTo("0");
    }
    private static void assertFrozenMatchesVerified(Map<String,List<Map<String,String>>> frozen,Map<String,List<Map<String,String>>> verified){
        assertThat(verified.keySet()).containsExactlyInAnyOrderElementsOf(OWNED);
        assertThat(frozen).as("All frozen members/columns must match the rows validated before logout").isEqualTo(verified);
    }
    private static void frozenDriftGuardCheck(){
        var verified=new LinkedHashMap<String,List<Map<String,String>>>();for(String table:OWNED)verified.put(table,List.of());
        verified.put("checkout_quote",List.of(Map.of("id","1","customer_id","4","snapshot_json","validated","created_at","fixed")));
        assertFrozenMatchesVerified(new LinkedHashMap<>(verified),verified);
        for(String column:List.of("id","customer_id","snapshot_json","created_at")){
            var drifted=new LinkedHashMap<>(verified);var changed=new LinkedHashMap<>(verified.get("checkout_quote").getFirst());changed.put(column,"third-party-drift");drifted.put("checkout_quote",List.of(changed));
            assertThatThrownBy(()->assertFrozenMatchesVerified(drifted,verified)).isInstanceOf(AssertionError.class);
        }
    }
    private void cleanup(Map<String,List<Map<String,String>>> frozen) throws Exception {
        note(Map.of("stage","exact-cleanup-intent","resourceDigests",digests(frozen)));
        var tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(30);
        tx.executeWithoutResult(status->{
            // Every PRIMARY full-range lock and whole-member/all-column comparison precedes the first DELETE.
            for(String table:OWNED)assertThat(rows(table,true)).isEqualTo(frozen.get(table));
            GUARD.phase="resume-retained".equals(System.getenv("M1_QUOTE_HTTP_INTEGRATION"))?"retained-cleanup":"cleanup";
            try {for(String table:List.of("audit_log","idempotency_request","checkout_quote","customer_address","stock_balance"))for(var row:frozen.get(table)){
                // Literal allowlisted table/column identifiers; null-safe binary comparison binds EVERY frozen value,
                // including PK, owner, random identity, snapshots, time, scope, trace and ciphertext (hex).
                var predicates=new ArrayList<String>();var args=new ArrayList<Object>();
                for(var column:row.entrySet()){
                    String name=column.getKey();if(!name.matches("[a-z_]+"))throw new IllegalStateException("Unexpected column identifier");
                    boolean binary=name.equals("encrypted_response");
                    predicates.add((binary?"HEX(`"+name+"`)":"CAST(`"+name+"` AS BINARY)")+" <=> "+(binary?"UPPER(?)":"CAST(? AS BINARY)"));args.add(column.getValue());
                }
                assertThat(jdbc.update("DELETE FROM `"+table+"` WHERE "+String.join(" AND ",predicates),args.toArray())).isEqualTo(1);
            } originalUnchanged(TABLES);}finally{GUARD.phase="closed";}
        });
    }
    private static void physical(Path path) throws Exception {for(Path current=path;current!=null;current=current.getParent())if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)){
        if(Files.isSymbolicLink(current)||Files.readAttributes(current,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isOther()||!current.toRealPath().equals(current.toAbsolutePath().normalize()))throw new IllegalStateException("Evidence reparse ancestor rejected");}}
    private void retained(Throwable failure) throws Exception {GUARD.phase="closed";note(Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName(),"sqlAttempts",new TreeMap<>(GUARD.counts),"blockedSqlWrites",GUARD.blocked));}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    private Map<String,List<Map<String,String>>> snapshot(){var result=new LinkedHashMap<String,List<Map<String,String>>>();for(String table:OWNED)result.put(table,rows(table,false));return Collections.unmodifiableMap(result);}
    private void verifyDatabaseStructure(){
        assertThat(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getURL())).isEqualTo(CANONICAL);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+00:00");
        var tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",String.class);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
        int[] checksums={-357524081,502337516,174647045,-493016158,237973485,947094408,-458546823};
        var history=jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");assertThat(history).hasSize(7);
        for(int i=0;i<7;i++){assertThat(history.get(i).get("version").toString()).isEqualTo(Integer.toString(i+1));assertThat(((Number)history.get(i).get("checksum")).intValue()).isEqualTo(checksums[i]);assertThat(history.get(i).get("success").toString()).isIn("true","1");}
        for(var expected:Map.of("account",4,"category",15,"brand",26,"product",1000,"sku",1000,"asset",120,"product_asset",1000).entrySet())assertThat(count(expected.getKey())).as("Reviewed baseline count: %s",expected.getKey()).isEqualTo(expected.getValue());
    }
    private List<Map<String,String>> rows(String table,boolean lock){
        return jdbc.query("SELECT * FROM `"+table+"`"+(lock?" FORCE INDEX (PRIMARY) ORDER BY id FOR UPDATE":""),rs->{
            var result=new ArrayList<Map<String,String>>();int columns=rs.getMetaData().getColumnCount();
            while(rs.next()){
                var row=new LinkedHashMap<String,String>();
                for(int c=1;c<=columns;c++){
                    Object value=rs.getObject(c);String text;
                    if(value==null)text=null;else if(value instanceof byte[] bytes)text=HexFormat.of().formatHex(bytes);
                    else if(value instanceof Blob blob)text=HexFormat.of().formatHex(blob.getBytes(1,Math.toIntExact(blob.length())));
                    else text=rs.getString(c);
                    row.put(rs.getMetaData().getColumnLabel(c),text);
                }
                result.add(Collections.unmodifiableMap(row));
            }
            result.sort(Comparator.comparing(this::rowDigest));return List.copyOf(result);
        });
    }
    private String rowDigest(Map<String,String> row){var value=new StringBuilder();row.forEach((key,text)->value.append(key.length()).append(':').append(key).append(':').append(text==null?-1:text.length()).append(':').append(text==null?"":text).append(';'));return sha(value.toString());}
    private String digest(List<Map<String,String>> rows){return sha(String.join("\n",rows.stream().map(this::rowDigest).sorted().toList()));}
    private Map<String,String> digests(Map<String,List<Map<String,String>>> rows){var result=new LinkedHashMap<String,String>();rows.forEach((table,value)->result.put(table,digest(value)));return result;}
    private void originalUnchanged(List<String> tables){for(String table:tables)assertThat(digest(rows(table,false))).as("Original full-row digest: %s",table).isEqualTo(baseline.get(table));}
    private String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private String shaBytes(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private synchronized void note(Map<String,?> event) throws Exception {byte[] bytes=(json.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8);try(FileChannel file=FileChannel.open(manifest,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);}}

    @TestConfiguration static class GuardConfiguration {
        @Bean static BeanPostProcessor datasourceSqlGuard(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){
                if(bean instanceof DataSource ds)return GUARD.wrap(ds);return bean;
            }
        };}
    }
    /** Covers real JdbcTemplate AND MyBatis JDBC, before execute; refuses batches/calls/unwrap escape. */
    static class SqlGuard {
        volatile String phase="closed";int blocked;final Map<String,Integer> counts=new TreeMap<>();final Set<String> retainedHashes=new HashSet<>();final String mode;
        static final Map<String,Integer> BUSINESS=Map.of("INSERT checkout_quote",1,"INSERT idempotency_request",4,"UPDATE idempotency_request",4,"INSERT audit_log",2);
        static final String RETAINED_INSERT="INSERT INTO idempotency_request(actor_scope,method,route,key_hash,request_hash,owner_token,state,lease_until,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,'IN_PROGRESS',?,?,?,?)";
        static final String RETAINED_COMPLETE="UPDATE idempotency_request SET state='COMPLETED',http_status=?,encrypted_response=?,updated_at=?,expires_at=? WHERE id=CAST(? AS UNSIGNED) AND owner_token=? AND state='IN_PROGRESS'";
        static final String TOKEN_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        SqlGuard(){this(System.getenv("M1_QUOTE_HTTP_INTEGRATION"));}
        SqlGuard(String mode){this.mode=mode;}
        synchronized void allowRetainedHash(String hash){if(!phase.equals("retained-business")||!hash.matches("[0-9a-f]{64}")||retainedHashes.size()>=2||!retainedHashes.add(hash))reject();}
        synchronized void check(String sql,Map<Integer,Object> bound){
            String normalized=sql.strip().replaceAll("\\s+"," ").replace("`","");
            if(normalized.matches("(?is)SELECT .*")){
                if(normalized.matches("(?is).*\\b(INTO|OUTFILE|DUMPFILE|SLEEP|GET_LOCK|RELEASE_LOCK|LOAD_FILE)\\b.*")||normalized.contains(";")||normalized.contains("/*"))reject();return;
            }
            String[] words=normalized.split(" ");String key=words.length>=3&&words[0].equalsIgnoreCase("INSERT")&&words[1].equalsIgnoreCase("INTO")?"INSERT "+words[2].split("\\(")[0].toLowerCase(Locale.ROOT):words.length>=2&&words[0].equalsIgnoreCase("UPDATE")?"UPDATE "+words[1].toLowerCase(Locale.ROOT):words.length>=3&&words[0].equalsIgnoreCase("DELETE")&&words[1].equalsIgnoreCase("FROM")?"DELETE "+words[2].toLowerCase(Locale.ROOT):"";
            int limit=0;
            if("approved-single-quote-http".equals(mode)){
                if(phase.equals("business"))limit=BUSINESS.getOrDefault(key,0);
                if(phase.equals("seed")&&Set.of("INSERT customer_address","INSERT stock_balance").contains(key))limit=1;
                if(phase.equals("cleanup")&&key.startsWith("DELETE ")&&OWNED.contains(key.substring(7))&&normalized.contains(" WHERE "))limit=key.equals("DELETE idempotency_request")?3:1;
            }
            if("resume-retained".equals(mode)){
                if(phase.equals("retained-business"))limit=Map.of("INSERT idempotency_request",2,"UPDATE idempotency_request",2).getOrDefault(key,0);
                if(phase.equals("retained-cleanup")&&key.startsWith("DELETE ")&&OWNED.contains(key.substring(7))&&normalized.contains(" WHERE "))limit=key.equals("DELETE idempotency_request")?4:1;
                if(phase.equals("retained-business")&&key.equals("INSERT idempotency_request")&&
                    (!normalized.equalsIgnoreCase(RETAINED_INSERT)||!"CUSTOMER:4".equals(String.valueOf(bound.get(1)))||
                     !"POST".equals(String.valueOf(bound.get(2)))||!ROUTE.equals(String.valueOf(bound.get(3)))||
                     !retainedHashes.contains(String.valueOf(bound.get(4)))||!String.valueOf(bound.get(6)).matches(TOKEN_PATTERN)))reject();
                if(phase.equals("retained-business")&&key.equals("UPDATE idempotency_request")&&
                    (!normalized.equalsIgnoreCase(RETAINED_COMPLETE)||!String.valueOf(bound.get(6)).matches(TOKEN_PATTERN)||
                     !String.valueOf(bound.get(5)).matches("[1-9][0-9]{0,18}")||
                     Set.of("133","134").contains(String.valueOf(bound.get(5)))))reject();
            }
            if(limit==0||counts.getOrDefault(key,0)>=limit||normalized.contains(";")||normalized.contains("/*"))reject();
            counts.merge(key,1,Integer::sum);
        }
        private void reject(){blocked++;throw new IllegalStateException("SQL write/unsafe statement rejected before execution by fixture phase/budget");}
        DataSource wrap(DataSource source){return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args)->{
            if(method.getName().equals("unwrap"))throw new SQLException("Unwrap disabled by fixture");
            Object result=invoke(source,method,args);return result instanceof Connection c?connection(c):result;
        });}
        Connection connection(Connection target){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            String name=method.getName();if(name.equals("unwrap")||name.equals("prepareCall"))throw new SQLException("SQL escape disabled by fixture");
            Object result=invoke(target,method,args);
            if(result instanceof DatabaseMetaData metadata)return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),new Class<?>[]{DatabaseMetaData.class},(metadataProxy,metadataMethod,metadataArgs)->{
                if(metadataMethod.getName().equals("getConnection"))return proxy;
                if(metadataMethod.getName().equals("unwrap"))throw new SQLException("Metadata unwrap disabled by fixture");
                return invoke(metadata,metadataMethod,metadataArgs);
            });
            if(result instanceof Statement statement)return statement(statement,args!=null&&args.length>0&&args[0] instanceof String?(String)args[0]:null,(Connection)proxy);
            return result;
        });}
        Object statement(Statement target,String prepared,Connection connection){
            Class<?> type=target instanceof PreparedStatement?PreparedStatement.class:Statement.class;
            Map<Integer,Object> bound=new HashMap<>();
            return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
                String name=method.getName();if(name.equals("unwrap")||name.equals("addBatch")||name.equals("executeBatch")||name.equals("executeLargeBatch"))throw new SQLException("SQL batch/unwrap disabled by fixture");
                if(name.equals("getConnection"))return connection;
                if(prepared!=null&&name.startsWith("set")&&args!=null&&args.length>=2&&args[0] instanceof Integer index)bound.put(index,args[1]);
                if(name.equals("clearParameters"))bound.clear();
                if(name.startsWith("execute")){
                    String sql=args!=null&&args.length>0&&args[0] instanceof String?(String)args[0]:prepared;if(sql==null)reject();check(sql,bound);
                    if("resume-retained".equals(mode)&&phase.equals("retained-business")&&
                       sql.strip().replaceAll("\\s+"," ").replace("`","").equalsIgnoreCase(RETAINED_COMPLETE))verifyRetainedTarget(target.getConnection(),bound);
                }
                return invoke(target,method,args);
            });
        }
        synchronized void verifyRetainedTarget(Connection connection,Map<Integer,Object> bound) throws SQLException {
            String id=String.valueOf(bound.get(5));
            try(PreparedStatement probe=connection.prepareStatement("SELECT key_hash,actor_scope,method,route,owner_token,state FROM idempotency_request WHERE id=CAST(? AS UNSIGNED) FOR UPDATE NOWAIT")){
                probe.setString(1,id);
                try(ResultSet row=probe.executeQuery()){
                    if(!row.next()||!retainedHashes.contains(row.getString(1))||!"CUSTOMER:4".equals(row.getString(2))||
                       !"POST".equals(row.getString(3))||!ROUTE.equals(row.getString(4))||!String.valueOf(bound.get(6)).equals(row.getString(5))||
                       !"IN_PROGRESS".equals(row.getString(6))||row.next())reject();
                }
            }
        }
        static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    }
}
