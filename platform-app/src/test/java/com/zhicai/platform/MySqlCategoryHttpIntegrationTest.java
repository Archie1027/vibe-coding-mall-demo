package com.zhicai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.write.WriteRequestFingerprint;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

/** Writes only approved UUID-named temporary categories, then deletes children before parents. */
@EnabledIfEnvironmentVariable(named="M1_CATEGORY_MYSQL_INTEGRATION", matches="approved-temporary-categories")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="spring.flyway.enabled=false")
class MySqlCategoryHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    final String prefix = "ZC-M1C-TEST-" + UUID.randomUUID();
    final Set<String> names = new HashSet<>(), traces = new HashSet<>();
    final Set<RequestRef> owned = new HashSet<>();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    String actor, token, customerToken;
    boolean unknownResult;
    List<Map<String,Object>> baseline;
    record RequestRef(String method, String route, String key) {}
    record Reply(int status, String body, JsonNode json) {}

    @BeforeEach void before() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("zhicai_dev");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
        baseline = jdbc.queryForList("SELECT * FROM category ORDER BY id");
        token = login("staff", "admin", "M1_CATEGORY_ADMIN_PASSWORD");
        actor = send("GET", "/api/v1/me", null, null, token).json().at("/data/id").asText();
        assertThat(actor).matches("[1-9][0-9]{0,18}");
    }

    @Test void depthCycleSubtreeMoveReplayVersionAndAudit() throws Exception {
        String rootKey = key();
        Reply root = create("a", null, rootKey); String a = id(root);
        var explicitNull = json.createObjectNode().put("name", prefix + "-a").putNull("parentId").put("sort", 0).put("enabled", true);
        assertThat(send("POST", "/api/v1/admin/categories", json.writeValueAsString(explicitNull), rootKey, token).body()).isEqualTo(root.body());
        String b = id(create("b", a, key())), c = id(create("c", b, key())), d = id(create("d", null, key()));
        Reply fourth = create("fourth", c, key()); assertThat(fourth.status()).isEqualTo(409); assertThat(fourth.json().path("code").asText()).isEqualTo("CATEGORY_DEPTH_EXCEEDED");
        assertCode(update(a, "a", c, 0, true, 0, key()), "CATEGORY_CYCLE");
        assertCode(update(a, "a", d, 0, true, 0, key()), "CATEGORY_DEPTH_EXCEEDED");
        String moveKey = key(); Reply moved = update(b, "b", d, 7, false, 0, moveKey);
        assertThat(moved.status()).isEqualTo(200); assertThat(moved.json().at("/data/version").asLong()).isEqualTo(1);
        assertThat(update(b, "b", d, 7, false, 0, moveKey).body()).isEqualTo(moved.body());
        assertCode(update(b, "b", d, 7, false, 0, key()), "VERSION_CONFLICT");
        assertThat(create("missing-parent", "999999999999999999", key()).status()).isEqualTo(404);
        assertThat(update(b, "b", null, 8, true, 1, key()).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type='CATEGORY' AND resource_id=CAST(? AS UNSIGNED) AND JSON_UNQUOTE(JSON_EXTRACT(masked_before_after,'$.after.parentId'))=? AND JSON_EXTRACT(masked_before_after,'$.after.sort')=7", Integer.class, b, d)).isEqualTo(1);
        customerToken = login("customer", "customer", "M1_CATEGORY_CUSTOMER_PASSWORD");
        String body = json.writeValueAsString(Map.of("name", prefix + "-forbidden", "sort", 0, "enabled", true));
        assertThat(send("POST", "/api/v1/admin/categories", body, key(), customerToken).status()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/admin/categories", body, null, null).status()).isEqualTo(401);
        assertThat(send("POST", "/api/v1/admin/categories", body, null, token).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category", Integer.class)).isEqualTo(19);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request", Integer.class)).isEqualTo(11);
    }

    @Test void concurrentReciprocalMovesCannotCreateACycle() throws Exception {
        String x = id(create("x", null, key())), y = id(create("y", null, key()));
        String firstKey = key(), secondKey = key();
        CompletableFuture<HttpResponse<String>> first, second;
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement(); var ignored = statement.executeQuery("SELECT id FROM category ORDER BY id LIMIT 1 FOR UPDATE")) {
                first = http.sendAsync(request("PUT", "/api/v1/admin/categories/" + x, updateBody("x", y, 0, true, 0), firstKey, token), HttpResponse.BodyHandlers.ofString());
                second = http.sendAsync(request("PUT", "/api/v1/admin/categories/" + y, updateBody("y", x, 0, true, 0), secondKey, token), HttpResponse.BodyHandlers.ofString());
                long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                int claims;
                do {
                    claims = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request WHERE key_hash IN (?,?) AND state='IN_PROGRESS'", Integer.class, WriteRequestFingerprint.sha256(firstKey), WriteRequestFingerprint.sha256(secondKey));
                    if (claims < 2) Thread.sleep(20);
                } while (claims < 2 && System.nanoTime() < limit);
                assertThat(claims).isEqualTo(2);
            } finally { blocker.rollback(); }
        }
        Reply a, b;
        try { a = decode(first.get(8, TimeUnit.SECONDS)); b = decode(second.get(8, TimeUnit.SECONDS)); }
        catch (Exception error) { unknownResult = true; throw error; }
        assertThat(List.of(a.status(), b.status())).containsExactlyInAnyOrder(200, 409);
        assertCode(a.status() == 409 ? a : b, "CATEGORY_CYCLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE (id=CAST(? AS UNSIGNED) AND parent_id=CAST(? AS UNSIGNED)) OR (id=CAST(? AS UNSIGNED) AND parent_id=CAST(? AS UNSIGNED))", Integer.class, x, y, y, x)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isEqualTo(3);
    }

    @AfterEach void cleanup() throws Exception {
        if (actor == null) return;
        if (unknownResult) throw new IllegalStateException("HTTP outcome uncertain; preserve temporary rows for inspection");
        var rows = jdbc.queryForList("SELECT CAST(id AS CHAR) id,name,CAST(parent_id AS CHAR) parentId FROM category WHERE name LIKE ?", prefix + "%");
        Map<String,Map<String,Object>> remaining = new LinkedHashMap<>();
        for (var row : rows) {
            String resource = row.get("id").toString(); assertThat(names).contains(row.get("name").toString());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE category_id=CAST(? AS UNSIGNED)", Integer.class, resource)).isZero();
            remaining.put(resource, row);
        }
        for (String resource : remaining.keySet()) {
            var children = jdbc.queryForList("SELECT CAST(id AS CHAR) FROM category WHERE parent_id=CAST(? AS UNSIGNED)", String.class, resource);
            assertThat(remaining.keySet()).containsAll(children);
            var audits = jdbc.queryForList("SELECT CAST(id AS CHAR) id,trace_id,action FROM audit_log WHERE actor_id=CAST(? AS UNSIGNED) AND resource_type='CATEGORY' AND resource_id=CAST(? AS UNSIGNED)", actor, resource);
            for (var audit : audits) {
                assertThat(traces).contains(audit.get("trace_id").toString()); assertThat(audit.get("action").toString()).isIn("CATEGORY_CREATE", "CATEGORY_UPDATE");
                jdbc.update("DELETE FROM audit_log WHERE id=CAST(? AS UNSIGNED) AND actor_id=CAST(? AS UNSIGNED) AND resource_type='CATEGORY' AND resource_id=CAST(? AS UNSIGNED) AND trace_id=?", audit.get("id"), actor, resource, audit.get("trace_id"));
            }
        }
        while (!remaining.isEmpty()) {
            var leaf = remaining.values().stream().filter(row -> remaining.values().stream().noneMatch(child -> row.get("id").equals(child.get("parentId")))).findFirst().orElseThrow(() -> new IllegalStateException("Unexpected test cycle; preserve rows"));
            assertThat(jdbc.update("DELETE FROM category WHERE id=CAST(? AS UNSIGNED) AND name=?", leaf.get("id"), leaf.get("name"))).isEqualTo(1); remaining.remove(leaf.get("id").toString());
        }
        for (RequestRef ref : owned) jdbc.update("DELETE FROM idempotency_request WHERE actor_scope=? AND method=? AND route=? AND key_hash=?", "STAFF:" + actor, ref.method(), ref.route(), WriteRequestFingerprint.sha256(ref.key()));
        assertThat(jdbc.queryForList("SELECT * FROM category ORDER BY id")).isEqualTo(baseline);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request", Integer.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
        send("POST", "/api/v1/auth/logout", null, null, token); if (customerToken != null) send("POST", "/api/v1/auth/logout", null, null, customerToken);
    }
    private Reply create(String suffix, String parent, String key) throws Exception {
        names.add(prefix + "-" + suffix); var body = json.createObjectNode().put("name", prefix + "-" + suffix).put("sort", 0).put("enabled", true);
        if (parent != null) body.put("parentId", parent);
        return send("POST", "/api/v1/admin/categories", json.writeValueAsString(body), key, token);
    }
    private Reply update(String id, String suffix, String parent, int sort, boolean enabled, long version, String key) throws Exception { return send("PUT", "/api/v1/admin/categories/" + id, updateBody(suffix, parent, sort, enabled, version), key, token); }
    private String updateBody(String suffix, String parent, int sort, boolean enabled, long version) throws Exception { return json.writeValueAsString(json.createObjectNode().put("name", prefix + "-" + suffix).put("parentId", parent).put("sort", sort).put("enabled", enabled).put("version", version)); }
    private String login(String type, String username, String variable) throws Exception {
        String password = System.getenv(variable); if (password == null || password.isEmpty()) throw new IllegalStateException("Approved test credential missing: " + variable);
        Reply reply = send("POST", "/api/v1/auth/" + type + "/login", json.writeValueAsString(Map.of("username", username, "password", password)), null, null);
        assertThat(reply.status()).isEqualTo(200); return reply.json().at("/data/accessToken").asText();
    }
    private HttpRequest request(String method, String route, String body, String key, String bearer) {
        if (key != null && bearer != null && bearer.equals(token)) owned.add(new RequestRef(method, route, key));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route)).timeout(Duration.ofSeconds(8)).header("Accept", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer); if (key != null) request.header("Idempotency-Key", key); if (body != null) request.header("Content-Type", "application/json");
        return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private Reply send(String method, String route, String body, String key, String bearer) throws Exception {
        try { return decode(http.send(request(method, route, body, key, bearer), HttpResponse.BodyHandlers.ofString())); }
        catch (Exception error) { unknownResult = true; throw error; }
    }
    private Reply decode(HttpResponse<String> reply) throws Exception { JsonNode decoded = json.readTree(reply.body()); if (decoded.hasNonNull("traceId")) traces.add(decoded.path("traceId").asText()); return new Reply(reply.statusCode(), reply.body(), decoded); }
    private String id(Reply reply) { assertThat(reply.status()).isEqualTo(201); return reply.json().at("/data/id").asText(); }
    private String key() { return UUID.randomUUID().toString(); }
    private void assertCode(Reply reply, String code) { assertThat(reply.status()).isEqualTo(409); assertThat(reply.json().path("code").asText()).isEqualTo(code); }
}
