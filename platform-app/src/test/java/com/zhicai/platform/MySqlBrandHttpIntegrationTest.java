package com.zhicai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.BrandWriteService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.security.SecureRandom;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicitly approved temporary brand only. Uses real HTTP, Security, MyBatis, MySQL and Redis. */
@EnabledIfEnvironmentVariable(named="M1_BRAND_MYSQL_INTEGRATION", matches="approved-temporary-brand")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"spring.flyway.enabled=false", "app.catalog.writes-enabled=true"})
class MySqlBrandHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired BrandWriteService brands;
    static final String TEST_KEY;
    static { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); TEST_KEY = Base64.getEncoder().encodeToString(bytes); }
    @DynamicPropertySource static void replayKey(DynamicPropertyRegistry props) { props.add("app.catalog.replay-key", () -> TEST_KEY); }
    final String original = "ZC-M1B-TEST-" + UUID.randomUUID(), edited = original + "-edited";
    final Set<String> traces = new HashSet<>();
    final Set<RequestRef> owned = new HashSet<>();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    String actorId, staffToken, customerToken;
    record RequestRef(String method, String path, String key) {}
    record Reply(int status, String body, JsonNode json) {}

    @BeforeEach void baseline() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("zhicai_dev");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand", Integer.class)).isEqualTo(26);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
    }

    @Test void realHttpCreateEditReplayConflictPermissionsAndPublicVisibility() throws Exception {
        staffToken = login("staff", "admin", "M1_BRAND_ADMIN_PASSWORD");
        actorId = send("GET", "/api/v1/me", null, staffToken, null).json().at("/data/id").asText();
        assertThat(actorId).matches("[1-9][0-9]{0,18}");
        String path = "/api/v1/admin/brands", createKey = UUID.randomUUID().toString();
        String create = json.writeValueAsString(Map.of("name", original, "enabled", true));
        assertThat(send("POST", path, create, null, null).status()).isEqualTo(401);
        assertThat(send("POST", path, create, staffToken, null).status()).isEqualTo(400);
        Reply first = send("POST", path, create, staffToken, createKey);
        assertThat(first.status()).isEqualTo(201);
        String id = first.json().at("/data/id").asText(); assertThat(id).matches("[1-9][0-9]{0,18}");
        assertThat(first.json().at("/data/version").asLong()).isZero();
        assertThat(send("POST", path, create, staffToken, createKey).body()).isEqualTo(first.body());
        assertThat(send("POST", path, json.writeValueAsString(Map.of("name", edited, "enabled", true)), staffToken, createKey).json().path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        String updatePath = path + "/" + id, editKey = UUID.randomUUID().toString();
        String edit = json.writeValueAsString(Map.of("name", edited, "enabled", false, "version", 0));
        Reply changed = send("PUT", updatePath, edit, staffToken, editKey);
        assertThat(changed.status()).isEqualTo(200); assertThat(changed.json().at("/data/version").asLong()).isEqualTo(1);
        assertThat(send("PUT", updatePath, edit, staffToken, editKey).body()).isEqualTo(changed.body());
        assertThat(send("PUT", updatePath, edit, staffToken, UUID.randomUUID().toString()).json().path("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(send("GET", "/api/v1/catalog/brands?size=100", null, null, null).json().at("/data/items").toString()).doesNotContain(edited);
        Reply restored = send("PUT", updatePath, json.writeValueAsString(Map.of("name", edited, "enabled", true, "version", 1)), staffToken, UUID.randomUUID().toString());
        assertThat(restored.status()).isEqualTo(200); assertThat(restored.json().at("/data/version").asLong()).isEqualTo(2);
        assertThat(send("GET", "/api/v1/catalog/brands?size=100", null, null, null).json().at("/data/items").toString()).contains(edited);
        customerToken = login("customer", "customer", "M1_BRAND_CUSTOMER_PASSWORD");
        assertThat(send("POST", path, create, customerToken, UUID.randomUUID().toString()).status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand", Integer.class)).isEqualTo(27);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request", Integer.class)).isEqualTo(4);
        assertThatThrownBy(() -> brands.create("must-not-write-without-transaction", true))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @AfterEach void cleanupExactOwnedResources() throws Exception {
        if (actorId == null) return;
        // Identify only the UUID-named resource, even if HTTP failed before its ID was captured.
        var rows = jdbc.queryForList("SELECT CAST(id AS CHAR) id,name FROM brand WHERE name IN (?,?)", original, edited);
        assertThat(rows.size()).isLessThanOrEqualTo(1);
        for (var row : rows) {
            String id = row.get("id").toString();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE brand_id=CAST(? AS UNSIGNED)", Integer.class, id)).isZero();
            var audits = jdbc.queryForList("SELECT CAST(id AS CHAR) id,trace_id,action FROM audit_log WHERE actor_id=CAST(? AS UNSIGNED) AND resource_type='BRAND' AND resource_id=CAST(? AS UNSIGNED)", actorId, id);
            for (var audit : audits) {
                assertThat(audit.get("action").toString()).isIn("BRAND_CREATE", "BRAND_UPDATE");
                assertThat(traces).contains(audit.get("trace_id").toString());
                jdbc.update("DELETE FROM audit_log WHERE id=CAST(? AS UNSIGNED) AND actor_id=CAST(? AS UNSIGNED) AND resource_type='BRAND' AND resource_id=CAST(? AS UNSIGNED) AND trace_id=?",
                    audit.get("id"), actorId, id, audit.get("trace_id"));
            }
            assertThat(jdbc.update("DELETE FROM brand WHERE id=CAST(? AS UNSIGNED) AND name=?", id, row.get("name"))).isEqualTo(1);
        }
        for (RequestRef ref : owned)
            jdbc.update("DELETE FROM idempotency_request WHERE actor_scope=? AND method=? AND route=? AND key_hash=?", "STAFF:" + actorId, ref.method(), ref.path(), com.zhicai.platform.write.WriteRequestFingerprint.sha256(ref.key()));
        if (staffToken != null) send("POST", "/api/v1/auth/logout", null, staffToken, null);
        if (customerToken != null) send("POST", "/api/v1/auth/logout", null, customerToken, null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand", Integer.class)).isEqualTo(26);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
    }

    private String login(String type, String username, String variable) throws Exception {
        String password = System.getenv(variable);
        if (password == null || password.isEmpty()) throw new IllegalStateException("Approved test credential missing: " + variable);
        Reply response = send("POST", "/api/v1/auth/" + type + "/login", json.writeValueAsString(Map.of("username", username, "password", password)), null, null);
        assertThat(response.status()).isEqualTo(200);
        return response.json().at("/data/accessToken").asText();
    }
    private Reply send(String method, String path, String body, String token, String key) throws Exception {
        if (key != null && token != null && token.equals(staffToken)) owned.add(new RequestRef(method, path, key));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(8)).header("Accept", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        HttpResponse<String> reply = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode decoded = json.readTree(reply.body());
        if (decoded.hasNonNull("traceId")) traces.add(decoded.path("traceId").asText());
        return new Reply(reply.statusCode(), reply.body(), decoded);
    }
}
