package com.zhicai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhicai.platform.write.WriteRequestFingerprint;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Requires separate, scoped approval. All mutations target only UUID-owned test products/SKUs. */
@EnabledIfEnvironmentVariable(named="M1_PRODUCT_MYSQL_INTEGRATION", matches="approved-temporary-products")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="spring.flyway.enabled=false")
class MySqlProductHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final String prefix = "ZC-M1P-" + UUID.randomUUID();
    final Map<String,String> products = new LinkedHashMap<>(), skus = new LinkedHashMap<>();
    final Set<RequestRef> requests = new HashSet<>();
    final Set<String> traces = new HashSet<>();
    final Map<String,List<Map<String,Object>>> baseline = new LinkedHashMap<>();
    String actor, token, category, brand, asset;
    boolean unknown;
    record RequestRef(String method, String route, String key) {}
    record Reply(int status, String body, JsonNode json) {}
    @BeforeEach void baseline() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product",Integer.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku",Integer.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class)).isZero();
        for (String table : List.of("category","brand","asset","product","sku","product_asset","flyway_schema_history"))
            baseline.put(table,jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1,2"));
        category = jdbc.queryForObject("SELECT CAST(MIN(id) AS CHAR) FROM category WHERE enabled=TRUE",String.class);
        brand = jdbc.queryForObject("SELECT CAST(MIN(id) AS CHAR) FROM brand WHERE enabled=TRUE",String.class);
        asset = jdbc.queryForObject("SELECT CAST(MIN(id) AS CHAR) FROM asset WHERE visibility='PUBLIC'",String.class);
        String password = System.getenv("M1_PRODUCT_ADMIN_PASSWORD"); if (password == null || password.isBlank()) throw new IllegalStateException("Scoped test credential missing");
        Reply login = send("POST","/api/v1/auth/staff/login",json.writeValueAsString(Map.of("username","admin","password",password)),null,null);
        assertThat(login.status()).isEqualTo(200); token = login.json().at("/data/accessToken").asText();
        actor = get("/api/v1/me").json().at("/data/id").asText(); assertThat(actor).matches("[1-9][0-9]{0,18}");
    }
    @Test void lifecycleReplayConflictPublicationAndExactPrice() throws Exception {
        ObjectNode input = product("lifecycle"); String createKey = key();
        Reply created = write("POST","/api/v1/admin/products",input,createKey); String id = createdId(created);
        assertThat(created.json().at("/data/state").asText()).isEqualTo("DRAFT");
        assertThat(write("POST","/api/v1/admin/products",input,createKey).body()).isEqualTo(created.body());
        code(write("POST","/api/v1/admin/products",product("different"),createKey),"IDEMPOTENCY_CONFLICT");
        assertThat(get("/api/v1/catalog/products/"+id).status()).isEqualTo(404);
        code(state(id,"ON_SALE",0),"PRODUCT_NOT_PUBLISHABLE");
        ObjectNode sku = sku("-a",false); Reply skuCreated = write("POST","/api/v1/admin/products/"+id+"/skus",sku,key());
        String skuId = createdId(skuCreated); assertThat(skuCreated.json().at("/data/priceMinor").asText()).isEqualTo("9007199254740993");
        code(write("POST","/api/v1/admin/products/"+id+"/skus",sku("-b",true),key()),"SKU_SPECS_CONFLICT");
        ObjectNode collision = sku("-c",false); collision.put("code",jdbc.queryForObject("SELECT code FROM sku ORDER BY id LIMIT 1",String.class));
        collision.set("specs",json.valueToTree(List.of(Map.of("name","型号","value","different"))));
        code(write("POST","/api/v1/admin/products/"+id+"/skus",collision,key()),"SKU_CONFLICT");
        ObjectNode update = product("edited").put("version",0); Reply updated = write("PUT","/api/v1/admin/products/"+id,update,key());
        assertThat(updated.status()).isEqualTo(200); assertThat(updated.json().at("/data/version").asInt()).isEqualTo(1);
        code(write("PUT","/api/v1/admin/products/"+id,update,key()),"VERSION_CONFLICT");
        Reply on = state(id,"ON_SALE",1); assertThat(on.status()).isEqualTo(200);
        Reply visible = get("/api/v1/catalog/products/"+id); assertThat(visible.status()).isEqualTo(200);
        assertThat(visible.json().at("/data/description").asText()).isEqualTo("<script>plain text</script>");
        assertThat(visible.json().at("/data/skus/0/priceMinor").asText()).isEqualTo("9007199254740993");
        ObjectNode disabled = sku.deepCopy().put("enabled",false).put("version",0);
        code(write("PUT","/api/v1/admin/skus/"+skuId,disabled,key()),"PRODUCT_NOT_PUBLISHABLE");
        assertThat(state(id,"OFF_SALE",2).status()).isEqualTo(200);
        assertThat(get("/api/v1/catalog/products/"+id).status()).isEqualTo(404);
        String editKey = key(); Reply edited = write("PUT","/api/v1/admin/skus/"+skuId,disabled,editKey);
        assertThat(edited.status()).isEqualTo(200); assertThat(edited.json().at("/data/version").asInt()).isEqualTo(1);
        assertThat(write("PUT","/api/v1/admin/skus/"+skuId,disabled,editKey).body()).isEqualTo(edited.body());
        code(write("PUT","/api/v1/admin/skus/"+skuId,disabled,key()),"VERSION_CONFLICT");
        code(state(id,"ON_SALE",3),"PRODUCT_NOT_PUBLISHABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type='SKU' AND resource_id=CAST(? AS UNSIGNED) AND JSON_UNQUOTE(JSON_EXTRACT(masked_before_after,'$.before.priceMinor'))='9007199254740993' AND JSON_EXTRACT(masked_before_after,'$.after.enabled')=false",Integer.class,skuId)).isEqualTo(1);
    }
    @Test void concurrentReorderedSpecsProduceOnlyOneSku() throws Exception {
        String id = createdId(write("POST","/api/v1/admin/products",product("concurrency"),key()));
        String route = "/api/v1/admin/products/"+id+"/skus", firstKey = key(), secondKey = key();
        CompletableFuture<HttpResponse<String>> first, second;
        try (var lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT id FROM product WHERE id=CAST(? AS UNSIGNED) FOR UPDATE")) { statement.setString(1,id); try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); } }
            first = http.sendAsync(request("POST",route,json.writeValueAsString(sku("-first",false)),firstKey,token),HttpResponse.BodyHandlers.ofString());
            second = http.sendAsync(request("POST",route,json.writeValueAsString(sku("-second",true)),secondKey,token),HttpResponse.BodyHandlers.ofString());
            long until = System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while (jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request WHERE route=? AND state='IN_PROGRESS'",Integer.class,route) != 2 && System.nanoTime()<until) Thread.sleep(20);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request WHERE route=? AND state='IN_PROGRESS'",Integer.class,route)).isEqualTo(2);
            lock.commit();
        }
        List<Reply> replies;
        try { replies = List.of(decode(first.get(15,TimeUnit.SECONDS)),decode(second.get(15,TimeUnit.SECONDS))); }
        catch (Exception error) { unknown=true; throw error; }
        assertThat(replies.stream().map(Reply::status).sorted().toList()).containsExactly(201,409);
        code(replies.stream().filter(reply -> reply.status()==409).findFirst().orElseThrow(),"SKU_SPECS_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE product_id=CAST(? AS UNSIGNED)",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type='SKU'",Integer.class)).isEqualTo(1);
    }
    @AfterEach void cleanupOnlyProvenOwnedRows() throws Exception {
        if (baseline.isEmpty() || token == null) return;
        if (unknown) throw new IllegalStateException("Unknown HTTP result; preserve test records: " + prefix);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var audits = jdbc.queryForList("SELECT CAST(id AS CHAR) id,CAST(actor_id AS CHAR) actor,resource_type,CAST(resource_id AS CHAR) resource,action,trace_id FROM audit_log");
            var claims = jdbc.queryForList("SELECT CAST(id AS CHAR) id,actor_scope,method,route,key_hash,state FROM idempotency_request");
            for (var row : audits) {
                String type = row.get("resource_type").toString(), resource = row.get("resource").toString();
                assertThat(row.get("actor")).isEqualTo(actor); assertThat(traces).contains(row.get("trace_id").toString());
                assertThat(type.equals("PRODUCT") ? products.containsKey(resource) : type.equals("SKU") && skus.containsKey(resource)).isTrue();
                assertThat(row.get("action").toString()).isIn(type+"_CREATE",type+"_UPDATE",type+"_STATE");
            }
            for (var row : claims) {
                assertThat(row.get("actor_scope")).isEqualTo("STAFF:"+actor); assertThat(row.get("state")).isEqualTo("COMPLETED");
                assertThat(requests.stream().anyMatch(ref -> ref.method().equals(row.get("method")) && ref.route().equals(row.get("route")) && WriteRequestFingerprint.sha256(ref.key()).equals(row.get("key_hash")))).isTrue();
            }
            for (var entry : products.entrySet()) {
                assertThat(entry.getValue()).startsWith(prefix);
                assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id=CAST(? AS UNSIGNED) FOR UPDATE",String.class,entry.getKey())).isEqualTo(entry.getValue());
                assertThat(jdbc.queryForList("SELECT CAST(id AS CHAR) FROM sku WHERE product_id=CAST(? AS UNSIGNED)",String.class,entry.getKey())).allMatch(skus::containsKey);
                assertThat(jdbc.queryForList("SELECT CAST(asset_id AS CHAR) FROM product_asset WHERE product_id=CAST(? AS UNSIGNED)",String.class,entry.getKey())).containsExactly(asset);
            }
            for (var entry : skus.entrySet()) { assertThat(entry.getValue()).startsWith(prefix); assertThat(jdbc.queryForObject("SELECT code FROM sku WHERE id=CAST(? AS UNSIGNED)",String.class,entry.getKey())).isEqualTo(entry.getValue()); }
            for (var row : audits) assertThat(jdbc.update("DELETE FROM audit_log WHERE id=CAST(? AS UNSIGNED) AND actor_id=CAST(? AS UNSIGNED) AND resource_type=? AND resource_id=CAST(? AS UNSIGNED) AND trace_id=?",row.get("id"),actor,row.get("resource_type"),row.get("resource"),row.get("trace_id"))).isEqualTo(1);
            for (var row : claims) assertThat(jdbc.update("DELETE FROM idempotency_request WHERE id=CAST(? AS UNSIGNED) AND actor_scope=? AND method=? AND route=? AND key_hash=?",row.get("id"),"STAFF:"+actor,row.get("method"),row.get("route"),row.get("key_hash"))).isEqualTo(1);
            for (var entry : skus.entrySet()) assertThat(jdbc.update("DELETE FROM sku WHERE id=CAST(? AS UNSIGNED) AND code=?",entry.getKey(),entry.getValue())).isEqualTo(1);
            for (var entry : products.entrySet()) {
                assertThat(jdbc.update("DELETE FROM product_asset WHERE product_id=CAST(? AS UNSIGNED) AND asset_id=CAST(? AS UNSIGNED)",entry.getKey(),asset)).isEqualTo(1);
                assertThat(jdbc.update("DELETE FROM product WHERE id=CAST(? AS UNSIGNED) AND name=?",entry.getKey(),entry.getValue())).isEqualTo(1);
            }
            for (var entry : baseline.entrySet()) assertThat(jdbc.queryForList("SELECT * FROM "+entry.getKey()+" ORDER BY 1,2")).as(entry.getKey()).isEqualTo(entry.getValue());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request",Integer.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account",Integer.class)).isEqualTo(4);
            System.out.printf("PRODUCT_TEST_CLEANUP prefix=%s products=%d skus=%d claims=%d audits=%d%n",prefix,products.size(),skus.size(),claims.size(),audits.size());
        });
        send("POST","/api/v1/auth/logout",null,null,token);
    }
    ObjectNode product(String suffix) { return json.createObjectNode().put("name",prefix+"-"+suffix).put("categoryId",category).put("brandId",brand).put("description","<script>plain text</script>").put("featured",false).set("imageAssetIds",json.valueToTree(List.of(asset))); }
    ObjectNode sku(String suffix, boolean reversed) {
        var specs = reversed ? List.of(Map.of("name","颜色","value","黑"),Map.of("name","容量","value","1TB")) : List.of(Map.of("name","容量","value","1TB"),Map.of("name","颜色","value","黑"));
        return json.createObjectNode().put("code",prefix+suffix).put("priceMinor","9007199254740993").put("enabled",true).set("specs",json.valueToTree(specs));
    }
    Reply state(String id,String state,long version) throws Exception { return write("POST","/api/v1/admin/products/"+id+"/state",json.createObjectNode().put("state",state).put("version",version),key()); }
    Reply write(String method,String route,JsonNode body,String key) throws Exception { return send(method,route,json.writeValueAsString(body),key,token); }
    Reply get(String route) throws Exception { return send("GET",route,null,null,token); }
    HttpRequest request(String method,String route,String body,String key,String bearer) {
        if (key != null && Objects.equals(bearer,token)) requests.add(new RequestRef(method,route,key));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+route)).timeout(Duration.ofSeconds(15)).header("Accept","application/json");
        if (bearer != null) request.header("Authorization","Bearer "+bearer); if (key != null) request.header("Idempotency-Key",key); if (body != null) request.header("Content-Type","application/json");
        return request.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }
    Reply send(String method,String route,String body,String key,String bearer) throws Exception {
        try { return decode(http.send(request(method,route,body,key,bearer),HttpResponse.BodyHandlers.ofString())); }
        catch (Exception error) { unknown=true; throw error; }
    }
    Reply decode(HttpResponse<String> response) throws Exception {
        if (response.statusCode()>=500) unknown=true;
        JsonNode body=json.readTree(response.body()); if (body.hasNonNull("traceId")) traces.add(body.path("traceId").asText());
        JsonNode resource=body.path("data");
        if (response.statusCode()>=200 && response.statusCode()<300 && resource.has("id")) {
            String id=resource.path("id").asText();
            if (resource.has("state") && resource.path("name").asText().startsWith(prefix)) products.put(id,resource.path("name").asText());
            if (resource.has("productId") && resource.path("code").asText().startsWith(prefix)) skus.put(id,resource.path("code").asText());
        }
        return new Reply(response.statusCode(),response.body(),body);
    }
    String createdId(Reply reply) { assertThat(reply.status()).isEqualTo(201); assertThat(reply.json().at("/data/id").asText()).matches("[1-9][0-9]{0,18}"); return reply.json().at("/data/id").asText(); }
    String key() { return UUID.randomUUID().toString(); }
    void code(Reply reply,String code) { assertThat(reply.status()).isEqualTo(409); assertThat(reply.json().path("code").asText()).isEqualTo(code); }
}
