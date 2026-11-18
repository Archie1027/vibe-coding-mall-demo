package com.zhicai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.write.WriteRequestFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Approval-gated browser fixture. Failures deliberately retain ALL SQL evidence; no teardown cleanup. */
@EnabledIfEnvironmentVariable(named="M1_CUSTOMER_BROWSER_INTEGRATION", matches="approved-temporary-customer-browser|read-only-startup")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.flyway.enabled=false", "app.customer.writes-enabled=true", "app.catalog.writes-enabled=false",
    "app.catalog.asset-base-url=http://127.0.0.1:5173/assets/products/"})
class MySqlCustomerBrowserIntegrationTest {
    private static final String ADDRESSES="/api/v1/me/addresses", ITEMS="/api/v1/me/cart/items";
    private static final String REPLAY_KEY=Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32));
    private static final List<String> ORIGINAL_TABLES=List.of("account","role","permission","account_role","role_permission",
        "warehouse","account_warehouse","category","brand","asset","product","sku","product_asset","flyway_schema_history");
    private static final List<String> NEW_TABLES=List.of("customer_address","cart_item","idempotency_request","audit_log");
    private static final Set<String> SNAPSHOT_FIELDS=Set.of("id","version","isDefault","skuId","quantity","selected","valid","priceMinor");
    @DynamicPropertySource static void replayKey(DynamicPropertyRegistry properties) {
        properties.add("app.catalog.replay-key",()->REPLAY_KEY);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    @Autowired StringRedisTemplate redis;
    final String run=UUID.randomUUID().toString(), display="M1C-browser-"+run;
    final String username="m1cb"+run.replace("-","").substring(0,28);
    final String password=Base64.getUrlEncoder().withoutPadding().encodeToString(new SecureRandom().generateSeed(24));
    final Map<String,String> baseline=new LinkedHashMap<>();
    final Set<String> addressIds=new HashSet<>(), cartIds=new HashSet<>(), traces=new HashSet<>(), sessions=new HashSet<>();
    final Map<String,RequestRef> requestIds=new LinkedHashMap<>();
    final Set<RequestRef> requests=new HashSet<>();
    final Map<RequestRef,Set<String>> requestTraces=new HashMap<>();
    final Map<RequestRef,String> requestResources=new HashMap<>();
    String owner, origin;
    Path directory, manifest, copiedScript, node, playwright, chrome;
    record RequestRef(String actor,String method,String path,String key) {}

    @Test
    @EnabledIfEnvironmentVariable(named="M1_CUSTOMER_BROWSER_INTEGRATION", matches="read-only-startup")
    void readOnlyApplicationStartupSupportsCurrentSchema() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='5' AND success=1",Integer.class)).isEqualTo(237973485);
        assertThat(jdbc.queryForObject("SELECT CHARACTER_SET_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='account' AND column_name='username'",String.class)).isEqualTo("utf8mb4");
        preflight();
        baseline();
        assertOriginalRows();
        note(Map.of("stage","read-only-preflight-verified","accounts",count("account"),"baselineSha256",baseline));
    }

    @Test
    @EnabledIfEnvironmentVariable(named="M1_CUSTOMER_BROWSER_INTEGRATION", matches="approved-temporary-customer-browser")
    void approvedTemporaryCustomerBrowserScenario() throws Exception {
        // Preflight every external requirement BEFORE the first SQL write; never start a second attempt.
        preflight();
        try {
            baseline();
            assertThat(redis.hasKey(identityBudget())).isFalse();
            note(Map.of("stage","account-insert-intent","username",username,"displayName",display,"accountType","CUSTOMER"));
            assertThat(jdbc.update("INSERT INTO account(account_type,username,password_hash,display_name,enabled) VALUES('CUSTOMER',?,?,?,1)",
                username,passwords.encode(password),display)).isEqualTo(1);
            owner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' AND username=? AND display_name=?",String.class,username,display);
            assertThat(owner).matches("[1-9][0-9]{0,18}");
            note(Map.of("stage","account-created","id",owner,"username",username));
            executeBrowser();
            readBrowserEvidence();
            assertThat(addressIds).hasSize(2); assertThat(cartIds).hasSize(2);
            assertThat(count("account")).isEqualTo(5);
            // The browser itself must have removed both addresses and both cart rows.
            assertThat(count("customer_address")).isZero(); assertThat(count("cart_item")).isZero();
            assertOriginalRows();
            var audits=validateAudits();
            var claims=validateClaims();
            assertThat(sessions).hasSize(1);
            for(String session:sessions) assertThat(redis.hasKey(session)).as("Browser logout removed its session").isFalse();
            cleanupIdentityBudget();
            cleanupExactRows(audits,claims);
            assertOriginalRows();
            assertCatalogueCounts();
            for(String table:NEW_TABLES) assertThat(count(table)).isZero();
            note(Map.of("stage","cleaned-and-verified","baselineSha256",baseline,"accounts",count("account")));
        } catch(Exception | AssertionError failure) {
            note(Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName()));
            throw failure;
        }
    }

    private void preflight() throws Exception {
        String expected=required("M1_CUSTOMER_BROWSER_SCRIPT_SHA256");
        assertThat(expected).matches("[a-fA-F0-9]{64}");
        Path source=absoluteFile("M1_CUSTOMER_BROWSER_SCRIPT");
        node=absoluteFile("M1_CUSTOMER_BROWSER_NODE"); chrome=absoluteFile("M1_CUSTOMER_BROWSER_CHROME");
        assertThat(node.getFileName().toString()).isEqualToIgnoringCase("node.exe");
        assertThat(chrome.getFileName().toString()).isEqualToIgnoringCase("chrome.exe");
        playwright=Path.of(required("M1_CUSTOMER_BROWSER_PLAYWRIGHT_MODULE"));
        assertThat(playwright.isAbsolute()).isTrue();
        playwright=playwright.toRealPath();
        assertThat(Files.isDirectory(playwright)).isTrue();
        assertThat(json.readTree(Files.readString(playwright.resolve("package.json"))).path("name").asText()).isEqualTo("playwright");
        byte[] bytes=Files.readAllBytes(source);
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(sha).isEqualToIgnoringCase(expected);
        origin=System.getenv().getOrDefault("M1_CUSTOMER_BROWSER_ORIGIN","http://127.0.0.1:5173");
        assertThat(origin).isEqualTo("http://127.0.0.1:5173");
        Path root=Path.of(required("M1_CUSTOMER_BROWSER_EVIDENCE_ROOT"));
        assertThat(root.isAbsolute()).isTrue();
        Files.createDirectories(root); root=root.toRealPath();
        Path checkout=Path.of(".").toRealPath();
        assertThat(root.startsWith(checkout)).as("Evidence stays outside checkout, including junction targets").isFalse();
        directory=root.resolve("customer-browser-"+run); Files.createDirectory(directory);
        manifest=directory.resolve("manifest.jsonl"); Files.createFile(manifest);
        copiedScript=directory.resolve("browser.cjs");
        Files.write(copiedScript,bytes,StandardOpenOption.CREATE_NEW);
        assertThat(copiedScript.toFile().setReadOnly()).as("Freeze approved script bytes").isTrue();
        Files.createDirectory(directory.resolve("browser-tmp"));
        note(Map.of("stage","run-start","run",run,"scriptSha256",sha,"maxAccounts",1,"maxAddresses",2,
            "maxCartRows",2,"maxClaims",16,"maxAudits",16));
        var ping=HttpRequest.newBuilder(URI.create(origin+"/")).timeout(Duration.ofSeconds(5)).GET().build();
        // Vite serves HTTP/1.1; its upgrade handling stalls a Java h2c negotiation.
        var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        var response=client.send(ping,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Start Vite before fixture execution").isEqualTo(200);
        assertThat(response.body()).contains("/@vite/client");
    }
    private String required(String name) {
        String value=System.getenv(name);
        if(value==null || value.isBlank()) throw new IllegalStateException(name+" required before fixture writes");
        return value;
    }
    private Path absoluteFile(String name) throws Exception {
        Path path=Path.of(required(name)); assertThat(path.isAbsolute()).isTrue();
        path=path.toRealPath(); assertThat(Files.isRegularFile(path)).isTrue(); return path;
    }
    private void executeBrowser() throws Exception {
        var builder=new ProcessBuilder(node.toString(),copiedScript.toString());
        builder.directory(directory.toFile()); builder.redirectErrorStream(true);
        builder.redirectOutput(directory.resolve("browser.log").toFile());
        Map<String,String> environment=builder.environment();
        environment.put("M1C_BROWSER_ALLOW_WRITES","approved-temporary-customer-browser");
        environment.put("M1C_BROWSER_API_BASE","http://127.0.0.1:"+port);
        environment.put("M1C_BROWSER_ORIGIN",origin);
        environment.put("M1C_BROWSER_USERNAME",username); environment.put("M1C_BROWSER_PASSWORD",password);
        environment.put("M1C_BROWSER_OWNER",owner); environment.put("M1C_BROWSER_EVIDENCE",directory.toString());
        environment.put("M1C_BROWSER_MANIFEST",manifest.toString());
        environment.put("M1C_PLAYWRIGHT_MODULE",playwright.toString());
        environment.put("M1C_CHROME_EXECUTABLE",chrome.toString());
        environment.put("TEMP",directory.resolve("browser-tmp").toString());
        environment.put("TMP",directory.resolve("browser-tmp").toString());
        note(Map.of("stage","browser-start-intent"));
        Process child=builder.start();
        Set<ProcessHandle> ownedDescendants=new LinkedHashSet<>();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            ownedDescendants.addAll(child.descendants().toList());
            note(Map.of("stage","browser-started","pid",child.pid()));
            while(true) {
                // Retain observed handles while Node is alive: abnormal Node exit can orphan Chrome.
                ownedDescendants.addAll(child.descendants().toList());
                long remaining=deadline-System.nanoTime();
                if(remaining<=0) throw new IllegalStateException("Browser deadline exceeded; preserve all SQL");
                if(child.waitFor(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS)) break;
            }
            assertThat(child.exitValue()).as("One approved browser attempt; see external browser.log").isZero();
        } finally {
            // Kill only this fixture's child subtree; never touch unrelated browser/dev-server processes.
            ownedDescendants.addAll(child.descendants().toList());
            for(ProcessHandle descendant:ownedDescendants) if(descendant.isAlive()) descendant.destroyForcibly();
            if(child.isAlive()) child.destroyForcibly();
            Set<ProcessHandle> ownedProcesses=new LinkedHashSet<>(ownedDescendants); ownedProcesses.add(child.toHandle());
            long terminationDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            for(ProcessHandle process:ownedProcesses) {
                if(process.isAlive()) {
                    long remaining=terminationDeadline-System.nanoTime();
                    if(remaining<=0) throw new IllegalStateException("Owned child termination unknown; preserve all SQL");
                    process.onExit().get(remaining,TimeUnit.NANOSECONDS);
                }
                assertThat(process.isAlive()).as("Owned child subtree terminated").isFalse();
            }
        }
    }

    private void readBrowserEvidence() throws Exception {
        boolean passed=false;
        Set<String> responses=new HashSet<>();
        for(String line:Files.readAllLines(manifest,StandardCharsets.UTF_8)) {
            JsonNode event=json.readTree(line); assertThat(event).isNotNull(); assertThat(event.isObject()).isTrue();
            String stage=event.path("stage").asText();
            if(stage.equals("browser-request")) {
                String requestId=text(event,"requestId"), method=text(event,"method"), path=text(event,"path");
                assertThat(passed).isFalse(); assertThat(requestIds).doesNotContainKey(requestId);
                assertThat(requestId).matches("[0-9a-fA-F-]{36}");
                assertThat(method).isIn("GET","POST","PUT","DELETE");
                assertThat(path).startsWith("/api/v1/");
                String actor=event.path("actor").asText("");
                if(path.startsWith("/api/v1/me")) assertThat(actor).isEqualTo(owner);
                String key=event.path("key").asText("");
                RequestRef ref=new RequestRef(actor,method,path,key); requestIds.put(requestId,ref);
                if(!method.equals("GET") && path.startsWith("/api/v1/me/")) {
                    assertThat(actor).isEqualTo(owner); assertThat(key).matches("[A-Za-z0-9_-]{8,128}");
                    assertThat(text(event,"bodySha256")).matches("[a-f0-9]{64}");
                    assertThat(path.equals(ADDRESSES) || path.startsWith(ADDRESSES+"/") || path.equals(ITEMS) || path.startsWith(ITEMS+"/")).isTrue();
                    requests.add(ref); assertThat(requests.size()).isLessThanOrEqualTo(16);
                }
            } else if(stage.equals("browser-response-header")) {
                String id=text(event,"requestId"), trace=text(event,"traceId");
                assertThat(requestIds).containsKey(id); assertThat(responses.add(id)).isTrue();
                assertThat(trace).matches("[0-9a-fA-F-]{36}"); traces.add(trace);
                assertThat(event.path("status").isIntegralNumber()).isTrue();
                assertThat(event.path("status").asInt()).isBetween(200,499);
                RequestRef ref=requestIds.get(id);
                if(requests.contains(ref)) {
                    assertThat(event.path("status").asInt()).isEqualTo(ref.method().equals("POST")?201:200);
                    requestTraces.computeIfAbsent(ref,ignored->new HashSet<>()).add(trace);
                }
            } else if(stage.equals("browser-resource")) {
                assertThat(text(event,"actor")).isEqualTo(owner);
                String type=text(event,"resourceType"), id=text(event,"resourceId");
                assertThat(id).matches("[1-9][0-9]{0,18}"); assertThat(type).isIn("ADDRESS","CART");
                String requestId=text(event,"requestId"); assertThat(responses).contains(requestId);
                RequestRef ref=requestIds.get(requestId); assertThat(requests).contains(ref);
                assertThat(ref.method()).isIn("POST","PUT");
                assertThat(type).isEqualTo(resourceType(ref));
                String previous=requestResources.putIfAbsent(ref,id);
                if(previous!=null) assertThat(id).as("Replay binds the same resource").isEqualTo(previous);
                if(ref.method().equals("PUT")) assertThat(id).isEqualTo(pathResourceId(ref));
                Set<String> ids=type.equals("ADDRESS")?addressIds:cartIds; ids.add(id); assertThat(ids.size()).isLessThanOrEqualTo(2);
            } else if(stage.equals("browser-session")) {
                String session=text(event,"redisSessionKey"); assertThat(session).matches("zhicai:auth:session:[a-f0-9]{64}"); sessions.add(session);
            } else if(stage.equals("browser-checks-passed")) {
                assertThat(passed).isFalse(); assertThat(event.path("cases").isArray()).isTrue();
                assertThat(event.path("cases").size()).isGreaterThan(0);
                for(JsonNode scenario:event.path("cases")) assertThat(scenario.isTextual()).isTrue();
                passed=true;
            } else if(stage.startsWith("browser-") && (stage.contains("fail") || stage.contains("error"))) {
                throw new IllegalStateException("Browser reported a failed/unknown outcome; preserve all SQL");
            }
        }
        assertThat(passed).as("A zero exit code alone is insufficient").isTrue();
        assertThat(requestIds).isNotEmpty(); assertThat(responses).containsExactlyInAnyOrderElementsOf(requestIds.keySet());
        assertThat(requests).isNotEmpty(); assertThat(requestTraces.keySet()).containsExactlyInAnyOrderElementsOf(requests);
        for(RequestRef ref:requests) {
            // Derive DELETE resources from their exact paths; POST/PUT must also have response-bound evidence.
            String id=ref.method().equals("DELETE")?pathResourceId(ref):requestResources.get(ref);
            assertThat(id).isNotNull();
            assertThat(resourceType(ref).equals("ADDRESS")?addressIds:cartIds).contains(id);
        }
        note(Map.of("stage","browser-manifest-validated","requests",requestIds.size(),"distinctWriteScopes",requests.size(),
            "addressIds",new TreeSet<>(addressIds),"cartIds",new TreeSet<>(cartIds)));
    }
    private String text(JsonNode node,String name) {
        assertThat(node.path(name).isTextual()).as(name).isTrue();
        String value=node.path(name).asText(); assertThat(value).isNotBlank(); return value;
    }

    private List<Map<String,Object>> validateAudits() throws Exception {
        var rows=jdbc.queryForList("SELECT CAST(id AS CHAR) id,CAST(actor_id AS CHAR) actor,action,resource_type,CAST(resource_id AS CHAR) resource,trace_id,masked_before_after,result FROM audit_log");
        assertThat(rows).isNotEmpty(); assertThat(rows.size()).isLessThanOrEqualTo(16);
        assertThat(rows).as("Exactly one audit per distinct logical browser write").hasSize(requests.size());
        for(var row:rows) {
            assertThat(row.get("actor").toString()).isEqualTo(owner);
            String type=row.get("resource_type").toString(); assertThat(type).isIn("ADDRESS","CART");
            assertThat(type.equals("ADDRESS")?addressIds:cartIds).contains(row.get("resource").toString());
            assertThat(row.get("action").toString()).isIn(type+"_CREATE",type+"_UPDATE",type+"_DELETE");
            assertThat(row.get("result").toString()).isEqualTo("SUCCESS");
            assertThat(traces).contains(row.get("trace_id").toString());
            assertThat(requests.stream().filter(ref->matchesAudit(ref,row)).count())
                .as("Each audit binds exactly one request, resource, action and that request's response trace").isEqualTo(1);
            JsonNode masked=json.readTree(row.get("masked_before_after").toString());
            assertThat(masked.isObject()).isTrue();
            assertThat(fields(masked)).containsExactlyInAnyOrder("before","after");
            for(String side:List.of("before","after")) {
                JsonNode snapshot=masked.get(side); assertThat(snapshot.isNull() || snapshot.isObject()).isTrue();
                if(snapshot.isObject()) {
                    assertThat(SNAPSHOT_FIELDS).containsAll(fields(snapshot));
                    for(JsonNode value:snapshot) assertThat(value.isValueNode()).isTrue();
                }
            }
        }
        for(RequestRef ref:requests) assertThat(rows.stream().filter(row->matchesAudit(ref,row)).count())
            .as("Each distinct write has its own exact audit: %s %s",ref.method(),ref.path()).isEqualTo(1);
        note(Map.of("stage","audit-privacy-validated","count",rows.size())); return rows;
    }
    private String resourceType(RequestRef ref) { return ref.path().startsWith(ADDRESSES)?"ADDRESS":"CART"; }
    private String pathResourceId(RequestRef ref) {
        String prefix=resourceType(ref).equals("ADDRESS")?ADDRESSES+"/":ITEMS+"/";
        assertThat(ref.path()).startsWith(prefix);
        String id=ref.path().substring(prefix.length()); assertThat(id).matches("[1-9][0-9]{0,18}"); return id;
    }
    private boolean matchesAudit(RequestRef ref,Map<String,Object> row) {
        String type=resourceType(ref), action=type+switch(ref.method()) {
            case "POST"->"_CREATE"; case "PUT"->"_UPDATE"; case "DELETE"->"_DELETE";
            default->throw new IllegalStateException("Unexpected browser write method");
        };
        String id=ref.method().equals("DELETE")?pathResourceId(ref):requestResources.get(ref);
        return row.get("actor").equals(ref.actor()) && row.get("resource_type").equals(type)
            && row.get("action").equals(action) && row.get("resource").equals(id)
            && requestTraces.getOrDefault(ref,Set.of()).contains(row.get("trace_id").toString());
    }
    private Set<String> fields(JsonNode node) { Set<String> fields=new HashSet<>(); node.fieldNames().forEachRemaining(fields::add); return fields; }
    private List<Map<String,Object>> validateClaims() {
        var rows=jdbc.queryForList("SELECT CAST(id AS CHAR) id,actor_scope,method,route,key_hash,state FROM idempotency_request");
        assertThat(rows).isNotEmpty(); assertThat(rows.size()).isLessThanOrEqualTo(16);
        assertThat(rows).as("One COMPLETED claim per distinct logical browser write").hasSize(requests.size());
        for(var row:rows) {
            assertThat(row.get("state").toString()).isEqualTo("COMPLETED");
            assertThat(row.get("actor_scope").toString()).isEqualTo("CUSTOMER:"+owner);
            assertThat(requests.stream().filter(ref->matchesClaim(ref,row)).count()).isEqualTo(1);
        }
        for(RequestRef ref:requests) assertThat(rows.stream().filter(row->matchesClaim(ref,row)).count())
            .as("Each distinct write has its own exact claim: %s %s",ref.method(),ref.path()).isEqualTo(1);
        return rows;
    }
    private boolean matchesClaim(RequestRef ref,Map<String,Object> row) {
        return row.get("actor_scope").equals("CUSTOMER:"+ref.actor()) && row.get("method").equals(ref.method())
            && row.get("route").equals(ref.path()) && row.get("key_hash").equals(WriteRequestFingerprint.sha256(ref.key()));
    }
    private void cleanupExactRows(List<Map<String,Object>> audits,List<Map<String,Object>> claims) throws Exception {
        assertThat(count("customer_address")).isZero(); assertThat(count("cart_item")).isZero();
        assertOriginalRows();
        note(Map.of("stage","exact-cleanup-intent","owner",owner,"username",username,"displayName",display,
            "auditIds",audits.stream().map(row->row.get("id")).toList(),"claimIds",claims.stream().map(row->row.get("id")).toList()));
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            // Lock and revalidate the entire candidate sets before any DELETE. Unknown rows retain everything.
            assertThat(jdbc.queryForList("SELECT CAST(id AS CHAR) FROM audit_log FOR UPDATE",String.class))
                .containsExactlyInAnyOrderElementsOf(audits.stream().map(row->row.get("id").toString()).toList());
            assertThat(jdbc.queryForList("SELECT CAST(id AS CHAR) FROM idempotency_request FOR UPDATE",String.class))
                .containsExactlyInAnyOrderElementsOf(claims.stream().map(row->row.get("id").toString()).toList());
            for(var row:audits) assertThat(jdbc.update("DELETE FROM audit_log WHERE id=CAST(? AS UNSIGNED) AND actor_id=CAST(? AS UNSIGNED) AND resource_type=? AND resource_id=CAST(? AS UNSIGNED) AND action=? AND trace_id=? AND result='SUCCESS' AND masked_before_after=CAST(? AS JSON)",
                row.get("id"),owner,row.get("resource_type"),row.get("resource"),row.get("action"),row.get("trace_id"),row.get("masked_before_after").toString())).isEqualTo(1);
            for(var row:claims) assertThat(jdbc.update("DELETE FROM idempotency_request WHERE id=CAST(? AS UNSIGNED) AND actor_scope=? AND method=? AND route=? AND key_hash=? AND state='COMPLETED'",
                row.get("id"),row.get("actor_scope"),row.get("method"),row.get("route"),row.get("key_hash"))).isEqualTo(1);
            assertThat(jdbc.update("DELETE FROM account WHERE id=CAST(? AS UNSIGNED) AND account_type='CUSTOMER' AND username=? AND display_name=? AND enabled=1 AND version=0",owner,username,display)).isEqualTo(1);
            for(String table:NEW_TABLES) assertThat(count(table)).isZero();
            assertThat(count("account")).isEqualTo(4);
        });
    }
    private String identityBudget() { return "zhicai:auth:budget:v2:identity:"+WriteRequestFingerprint.sha256("CUSTOMER:"+username); }
    private void cleanupIdentityBudget() throws Exception {
        String key=identityBudget(); note(Map.of("stage","redis-exact-cleanup-intent","identityBudgetKey",key));
        var script=new DefaultRedisScript<>("""
            if redis.call('EXISTS',KEYS[1])==0 then return 0 end
            if redis.call('HLEN',KEYS[1])~=1 or redis.call('HGET',KEYS[1],'count')~='0' then return -1 end
            return redis.call('DEL',KEYS[1])
            """,Long.class);
        assertThat(redis.execute(script,List.of(key))).isIn(0L,1L); assertThat(redis.hasKey(key)).isFalse();
    }

    private void baseline() throws Exception {
        String url=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection->connection.getMetaData().getURL());
        assertThat(url!=null && url.matches("jdbc:mysql://127\\.0\\.0\\.1:3306/zhicai_dev(?:\\?.*)?")).isTrue();
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");
        assertCatalogueCounts(); for(String table:NEW_TABLES) assertThat(count(table)).isZero();
        var history=jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(history).hasSize(5); int[] checksums={-357524081,502337516,174647045,-493016158,237973485};
        for(int n=0;n<5;n++) {
            assertThat(history.get(n).get("version").toString()).isEqualTo(Integer.toString(n+1));
            assertThat(history.get(n).get("success").toString()).isIn("true","1");
            assertThat(((Number)history.get(n).get("checksum")).intValue()).isEqualTo(checksums[n]);
        }
        for(String table:ORIGINAL_TABLES) baseline.put(table,digest(table,false));
        note(Map.of("stage","baseline","baselineSha256",baseline));
    }
    private void assertCatalogueCounts() {
        assertThat(count("account")).isEqualTo(4); assertThat(count("product")).isEqualTo(1000);
        assertThat(count("sku")).isEqualTo(1000); assertThat(count("asset")).isEqualTo(120);
        assertThat(count("category")).isEqualTo(15); assertThat(count("brand")).isEqualTo(26);
        assertThat(count("product_asset")).isEqualTo(1000);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class); }
    private void assertOriginalRows() throws Exception {
        for(String table:ORIGINAL_TABLES) assertThat(digest(table,table.equals("account") && owner!=null))
            .as("Unchanged full original rows: %s",table).isEqualTo(baseline.get(table));
    }
    private String digest(String table,boolean excludeFixture) throws Exception {
        String sql="SELECT * FROM `"+table+"`"+(excludeFixture?" WHERE NOT(id=CAST(? AS UNSIGNED) AND account_type='CUSTOMER' AND username=? AND display_name=?)":"");
        List<String> hashes=jdbc.query(sql,rs->{
            List<String> rows=new ArrayList<>(); int columns=rs.getMetaData().getColumnCount();
            while(rs.next()) {
                StringBuilder row=new StringBuilder();
                for(int c=1;c<=columns;c++) {
                    Object value=rs.getObject(c); String text=value==null?"<null>":value instanceof byte[] bytes?Base64.getEncoder().encodeToString(bytes):value.toString();
                    row.append(c).append(':').append(value==null?-1:text.length()).append(':').append(text).append(';');
                }
                rows.add(WriteRequestFingerprint.sha256(row.toString()));
            }
            return rows;
        },excludeFixture?new Object[]{owner,username,display}:new Object[]{});
        Collections.sort(hashes); return WriteRequestFingerprint.sha256(String.join("\n",hashes));
    }
    private synchronized void note(Map<String,?> event) throws Exception {
        if(manifest==null) return;
        byte[] line=(json.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8);
        try(FileChannel file=FileChannel.open(manifest,StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
            ByteBuffer buffer=ByteBuffer.wrap(line); while(buffer.hasRemaining()) file.write(buffer); file.force(true);
        }
    }
}
