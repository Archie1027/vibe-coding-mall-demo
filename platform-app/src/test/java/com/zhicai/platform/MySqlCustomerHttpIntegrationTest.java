package com.zhicai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.CatalogQueryService;
import com.zhicai.platform.trade.AddressService;
import com.zhicai.platform.trade.CartService;
import com.zhicai.platform.trade.TradeModels.AddressInput;
import com.zhicai.platform.write.WriteRequestFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real HTTP fixture. Any failed/unknown outcome deliberately preserves evidence and data. */
@EnabledIfEnvironmentVariable(named="M1_CUSTOMER_MYSQL_INTEGRATION", matches="approved-temporary-customer")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.flyway.enabled=false", "app.customer.writes-enabled=true", "app.catalog.writes-enabled=false"})
class MySqlCustomerHttpIntegrationTest {
    private static final String ADDRESSES="/api/v1/me/addresses", CART="/api/v1/me/cart", ITEMS=CART+"/items";
    private static final String REPLAY_KEY=Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32));
    private static final List<String> ORIGINAL_TABLES=List.of("account","role","permission","account_role","role_permission",
        "warehouse","account_warehouse","category","brand","asset","product","sku","product_asset","flyway_schema_history");
    @DynamicPropertySource static void replayKey(DynamicPropertyRegistry properties) {
        properties.add("app.catalog.replay-key",()->REPLAY_KEY);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired CatalogQueryService catalog;
    @Autowired AddressService addresses;
    @Autowired CartService cart;
    @Autowired PlatformTransactionManager transactions;
    @Autowired StringRedisTemplate redis;
    final String run=UUID.randomUUID().toString(), display="M1C-"+run;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final Map<String,String> tokenOwners=new ConcurrentHashMap<>();
    final Set<String> traces=ConcurrentHashMap.newKeySet(), addressIds=ConcurrentHashMap.newKeySet(), cartIds=ConcurrentHashMap.newKeySet();
    final Set<RequestRef> requests=ConcurrentHashMap.newKeySet();
    final List<Actor> actors=new ArrayList<>();
    final Set<String> sessionKeys=new HashSet<>();
    final Map<String,String> baseline=new LinkedHashMap<>();
    Path manifest;
    int addressAttempts;
    record Actor(String id,String username,String token) {}
    record RequestRef(String actor,String method,String path,String key) {}
    record Reply(int status,String body,JsonNode node) { JsonNode data() { return node.path("data"); } }

    @Test void approvedTemporaryCustomerHttpScenario() throws Exception {
        // No @AfterEach: assertions, transport/JSON errors and 5xx never trigger automatic SQL cleanup.
        openManifest();
        try {
            baseline();
            assertThatThrownBy(()->addresses.create("1",input(false))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            assertThatThrownBy(()->cart.add("1","1",1)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            Actor a=createActor("a"), b=createActor("b");
            assertThat(a.id()).isNotEqualTo(b.id());
            assertThat(send("GET","/api/v1/me",null,a.token(),null).data().path("id").asText()).isEqualTo(a.id());
            assertThat(send("GET","/api/v1/me",null,b.token(),null).data().path("id").asText()).isEqualTo(b.id());
            assertThat(send("GET",ADDRESSES,null,null,null).status()).isEqualTo(401);
            assertThat(send("POST",ADDRESSES,body(false,null),null,key()).status()).isEqualTo(401);
            optionalStaffDenial();
            assertThat(send("POST",ADDRESSES,body(false,null),a.token(),null).status()).isEqualTo(400);
            assertThat(send("POST",ITEMS,json.writeValueAsString(Map.of("skuId","1","quantity",0)),a.token(),key()).status()).isEqualTo(400);
            addressScenario(a,b);
            cartScenario(a,b);
            assertThat(count("customer_address")).isEqualTo(19); // One of twenty removed through HTTP Ack.
            assertThat(count("cart_item")).isEqualTo(2); // One of three removed through HTTP Ack.
            assertBounds();
            assertOriginalRows();
            note(Map.of("stage","checks-passed","addressIds",new TreeSet<>(addressIds),"cartIds",new TreeSet<>(cartIds)));
            // Logout is exact token-scoped; a failure here retains every SQL fixture for investigation.
            for(Actor actor:actors) assertThat(send("POST","/api/v1/auth/logout",null,actor.token(),null).status()).isEqualTo(200);
            for(String session:sessionKeys) assertThat(redis.hasKey(session)).isFalse();
            cleanupExactIdentityBudgets();
            cleanupVerifiedOwnedRows();
            assertOriginalRows();
            for(String table:List.of("customer_address","cart_item","idempotency_request","audit_log")) assertThat(count(table)).isZero();
            note(Map.of("stage","cleaned-and-verified","baselineSha256",baseline));
        } catch(Exception | AssertionError failure) {
            note(Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName()));
            throw failure;
        }
    }

    private void addressScenario(Actor a,Actor b) throws Exception {
        String createKey=key(), createBody=body(true,null);
        Reply first=createAddress(a,createBody,createKey); assertThat(first.status()).isEqualTo(201);
        String firstId=id(first); addressIds.add(firstId);
        assertThat(send("POST",ADDRESSES,createBody,a.token(),createKey).body()).isEqualTo(first.body());
        code(send("POST",ADDRESSES,body(false,null),a.token(),createKey),409,"IDEMPOTENCY_CONFLICT");
        Reply second=createAddress(a,body(true,null),key()); assertThat(second.status()).isEqualTo(201);
        String secondId=id(second); addressIds.add(secondId);
        JsonNode displaced=findAddress(a,firstId);
        assertThat(displaced.path("isDefault").asBoolean()).isFalse();
        assertThat(displaced.path("version").asLong()).isEqualTo(1);
        code(send("PUT",ADDRESSES+"/"+firstId,body(false,0L),a.token(),key()),409,"VERSION_CONFLICT");
        code(send("PUT",ADDRESSES+"/"+firstId,body(false,1L),b.token(),key()),404,"NOT_FOUND");
        code(send("DELETE",ADDRESSES+"/"+firstId,null,b.token(),key()),404,"NOT_FOUND");
        assertThat(list(b,ADDRESSES,1,20).path("total").asLong()).isZero();
        JsonNode p1=list(a,ADDRESSES,1,1),p2=list(a,ADDRESSES,2,1);
        assertThat(p1.path("items").size()).isEqualTo(1); assertThat(p1.path("total").asLong()).isEqualTo(2);
        assertThat(p1.at("/items/0/id").asText()).isNotEqualTo(p2.at("/items/0/id").asText());
        // Both use the observed versions. Account-row serialization permits a version conflict on displacement.
        List<Reply> moves=concurrent(()->send("PUT",ADDRESSES+"/"+firstId,body(true,1L),a.token(),key()),
            ()->send("PUT",ADDRESSES+"/"+secondId,body(true,0L),a.token(),key()));
        for(Reply reply:moves) if(reply.status()!=200) code(reply,409,"VERSION_CONFLICT");
        assertThat(moves.stream().filter(r->r.status()==200).count()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_address WHERE customer_id=CAST(? AS UNSIGNED) AND is_default=1",Integer.class,a.id())).isLessThanOrEqualTo(1);
        for(int n=2;n<19;n++) { Reply created=createAddress(a,body(false,null),key()); assertThat(created.status()).isEqualTo(201); addressIds.add(id(created)); }
        // Nineteen committed addresses, then exactly two real HTTP creates race for the final slot.
        List<Reply> race=concurrent(()->createAddress(a,body(false,null),key()),()->createAddress(a,body(false,null),key()));
        assertThat(race.stream().filter(r->r.status()==201).count()).isEqualTo(1);
        for(Reply reply:race) { if(reply.status()==201) addressIds.add(id(reply)); else code(reply,409,"ADDRESS_LIMIT_EXCEEDED"); }
        JsonNode all=list(a,ADDRESSES,1,20);
        assertThat(all.path("items").size()).isEqualTo(20); assertThat(all.path("total").asLong()).isEqualTo(20);
        assertThat(count("customer_address")).isEqualTo(20);
        ack(send("DELETE",ADDRESSES+"/"+secondId,null,a.token(),key()));
    }

    private void cartScenario(Actor a,Actor b) throws Exception {
        List<String> skuIds=jdbc.queryForList("SELECT CAST(s.id AS CHAR) FROM sku s JOIN product p ON p.id=s.product_id JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id WHERE s.enabled=1 AND p.state='ON_SALE' AND c.enabled=1 AND b.enabled=1 ORDER BY s.id LIMIT 3",String.class);
        assertThat(skuIds).hasSize(3);
        var current=catalog.cartSkus(skuIds);
        List<Reply> merged=concurrent(()->add(a,skuIds.get(0),1),()->add(a,skuIds.get(0),1));
        for(Reply reply:merged) { assertThat(reply.status()).isEqualTo(201); cartIds.add(id(reply)); }
        assertThat(cartIds).hasSize(1);
        String lineId=cartIds.iterator().next();
        JsonNode line=list(a,CART,1,20).at("/items/0");
        assertThat(line.path("quantity").asInt()).isEqualTo(2);
        assertThat(send("PUT",ITEMS+"/"+lineId,json.writeValueAsString(Map.of("quantity",99,"selected",false)),a.token(),key()).status()).isEqualTo(200);
        code(add(a,skuIds.get(0),1),409,"CART_QUANTITY_EXCEEDED");
        line=list(a,CART,1,20).at("/items/0");
        assertThat(line.path("quantity").asInt()).isEqualTo(99); assertThat(line.path("selected").asBoolean()).isFalse();
        code(send("PUT",ITEMS+"/"+lineId,json.writeValueAsString(Map.of("quantity",1,"selected",false)),b.token(),key()),404,"NOT_FOUND");
        code(send("DELETE",ITEMS+"/"+lineId,null,b.token(),key()),404,"NOT_FOUND");
        assertThat(list(b,CART,1,20).path("total").asLong()).isZero();
        for(int n=1;n<3;n++) { Reply added=add(a,skuIds.get(n),1); assertThat(added.status()).isEqualTo(201); cartIds.add(id(added)); }
        Set<String> seen=new HashSet<>();
        for(int page=1;page<=3;page++) {
            JsonNode data=list(a,CART,page,1); assertThat(data.path("total").asLong()).isEqualTo(3); assertThat(data.path("items").size()).isEqualTo(1);
            JsonNode item=data.at("/items/0"); assertThat(seen.add(item.path("id").asText())).isTrue();
            var sku=current.get(item.path("skuId").asText()); assertThat(sku).isNotNull();
            assertThat(item.path("valid").asBoolean()).isTrue();
            assertThat(item.path("priceMinor").asText()).isEqualTo(sku.priceMinor());
            assertThat(item.path("productName").asText()).isEqualTo(sku.productName());
            assertThat(item.path("specs")).isEqualTo(json.valueToTree(sku.specs()));
        }
        assertThat(cartIds).hasSize(3);
        ack(send("DELETE",ITEMS+"/"+lineId,null,a.token(),key()));
    }

    private Reply createAddress(Actor actor,String body,String key) throws Exception {
        synchronized(this) { if(++addressAttempts>21) throw new IllegalStateException("Address HTTP attempt budget exceeded"); }
        Reply result=send("POST",ADDRESSES,body,actor.token(),key);
        if(result.status()==201) { addressIds.add(id(result)); note(Map.of("stage","address-created","id",id(result),"owner",actor.id())); }
        return result;
    }
    private Reply add(Actor actor,String sku,int quantity) throws Exception {
        Reply reply=send("POST",ITEMS,json.writeValueAsString(Map.of("skuId",sku,"quantity",quantity)),actor.token(),key());
        if(reply.status()==201) { cartIds.add(id(reply)); note(Map.of("stage","cart-saved","id",id(reply),"owner",actor.id(),"skuId",sku)); }
        return reply;
    }
    private JsonNode list(Actor actor,String path,int page,int size) throws Exception {
        Reply reply=send("GET",path+"?page="+page+"&size="+size,null,actor.token(),null);
        assertThat(reply.status()).isEqualTo(200); return reply.data();
    }
    private JsonNode findAddress(Actor actor,String id) throws Exception {
        for(JsonNode row:list(actor,ADDRESSES,1,20).path("items")) if(row.path("id").asText().equals(id)) return row;
        throw new AssertionError("Expected owned address absent");
    }
    private AddressInput input(boolean isDefault) { return new AddressInput("Fixture","10000000007","Fixture","Fixture","Fixture","Synthetic",isDefault); }
    private String body(boolean isDefault,Long version) throws Exception {
        var node=json.valueToTree(input(isDefault)); if(version!=null) ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("version",version);
        return json.writeValueAsString(node);
    }
    private String key() { return UUID.randomUUID().toString(); }
    private String id(Reply reply) { String id=reply.data().path("id").asText(); assertThat(id).matches("[1-9][0-9]{0,18}"); return id; }
    private void code(Reply reply,int status,String code) { assertThat(reply.status()).isEqualTo(status); assertThat(reply.node().path("code").asText()).isEqualTo(code); }
    private void ack(Reply reply) { assertThat(reply.status()).isEqualTo(200); assertThat(reply.data().path("accepted").asBoolean()).isTrue(); }

    private Actor createActor(String suffix) throws Exception {
        String username="m1c"+run.replace("-","").substring(0,27)+suffix;
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(new SecureRandom().generateSeed(24));
        note(Map.of("stage","account-insert-intent","username",username,"accountType","CUSTOMER","displayName",display));
        assertThat(jdbc.update("INSERT INTO account(account_type,username,password_hash,display_name,enabled) VALUES('CUSTOMER',?,?,?,1)",username,passwords.encode(secret),display)).isEqualTo(1);
        String id=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' AND username=? AND display_name=?",String.class,username,display);
        note(Map.of("stage","account-created","id",id,"username",username));
        String budget=identityBudget(username);
        assertThat(redis.hasKey(budget)).isFalse();
        note(Map.of("stage","login-intent","owner",id,"identityBudgetKey",budget));
        Reply login=send("POST","/api/v1/auth/customer/login",json.writeValueAsString(Map.of("username",username,"password",secret)),null,null);
        assertThat(login.status()).isEqualTo(200);
        String token=login.data().path("accessToken").asText(); assertThat(token).matches("[A-Za-z0-9_-]{43}");
        tokenOwners.put(token,id);
        String session="zhicai:auth:session:"+WriteRequestFingerprint.sha256(token); sessionKeys.add(session);
        note(Map.of("stage","session-created","owner",id,"redisSessionKey",session));
        Actor actor=new Actor(id,username,token); actors.add(actor); return actor;
    }
    private void optionalStaffDenial() throws Exception {
        String password=System.getenv("M1_CUSTOMER_ADMIN_PASSWORD");
        if(password==null || password.isBlank()) { note(Map.of("stage","staff-http-check-skipped","reason","No optional private credential; prior read-only staff check is separate evidence")); return; }
        Reply login=send("POST","/api/v1/auth/staff/login",json.writeValueAsString(Map.of("username","admin","password",password)),null,null);
        assertThat(login.status()).isEqualTo(200); String token=login.data().path("accessToken").asText();
        String session="zhicai:auth:session:"+WriteRequestFingerprint.sha256(token); sessionKeys.add(session);
        note(Map.of("stage","staff-session-created","redisSessionKey",session));
        assertThat(send("GET",ADDRESSES,null,token,null).status()).isEqualTo(403);
        assertThat(send("POST",ITEMS,json.writeValueAsString(Map.of("skuId","1","quantity",1)),token,key()).status()).isEqualTo(403);
        assertThat(send("POST","/api/v1/auth/logout",null,token,null).status()).isEqualTo(200);
    }

    private Reply send(String method,String path,String body,String token,String key) throws Exception {
        String requestId=UUID.randomUUID().toString(), owner=token==null?null:tokenOwners.get(token);
        if(owner!=null && key!=null) {
            requests.add(new RequestRef(owner,method,path,key));
            if(requests.size()>64) throw new IllegalStateException("Claim budget exceeded");
        }
        Map<String,Object> intent=new LinkedHashMap<>(); intent.put("stage","http-intent"); intent.put("requestId",requestId);
        intent.put("method",method); intent.put("path",path); if(owner!=null) intent.put("actor",owner); if(key!=null) intent.put("key",key);
        note(intent); // Durable before HTTP dispatch; payloads/credentials/tokens are never written.
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Accept","application/json");
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(key!=null) builder.header("Idempotency-Key",key);
        if(body!=null) builder.header("Content-Type","application/json");
        var response=http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        String trace=response.headers().firstValue("X-Trace-Id").orElse("");
        note(Map.of("stage","http-response","requestId",requestId,"status",response.statusCode(),"traceId",trace));
        assertThat(trace).matches("[0-9a-fA-F-]{36}"); traces.add(trace);
        if(response.statusCode()>=500) throw new IllegalStateException("Unknown HTTP outcome; preserve all fixtures, requestId="+requestId);
        JsonNode parsed=json.readTree(response.body());
        if(parsed==null || !parsed.isObject()) throw new IllegalStateException("Unparseable HTTP outcome; preserve fixtures");
        return new Reply(response.statusCode(),response.body(),parsed);
    }
    private List<Reply> concurrent(Callable<Reply> first,Callable<Reply> second) throws Exception {
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Reply>> futures=new ArrayList<>();
            for(Callable<Reply> operation:List.of(first,second)) futures.add(pool.submit(()->{
                ready.countDown();
                if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start was not released; no request dispatched");
                return operation.call();
            }));
            if(!ready.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent workers not ready"); start.countDown();
            List<Reply> replies=new ArrayList<>(); for(Future<Reply> future:futures) replies.add(future.get(40,TimeUnit.SECONDS)); return replies;
        }
    }

    private void baseline() throws Exception {
        String url=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection->connection.getMetaData().getURL());
        assertThat(url!=null && url.matches("jdbc:mysql://127\\.0\\.0\\.1:3306/zhicai_dev(?:\\?.*)?"))
            .as("Only the approved local MySQL endpoint is permitted").isTrue();
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");
        assertThat(count("account")).isEqualTo(4); assertThat(count("product")).isEqualTo(1000); assertThat(count("sku")).isEqualTo(1000);
        assertThat(count("asset")).isEqualTo(120); assertThat(count("brand")).isEqualTo(26); assertThat(count("category")).isEqualTo(15);
        for(String table:List.of("customer_address","cart_item","idempotency_request","audit_log")) assertThat(count(table)).isZero();
        var history=jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(history).hasSize(4);
        int[] checksums={-357524081,502337516,174647045};
        for(int n=0;n<4;n++) { assertThat(history.get(n).get("version").toString()).isEqualTo(Integer.toString(n+1)); assertThat(history.get(n).get("success").toString()).isIn("true","1"); if(n<3) assertThat(((Number)history.get(n).get("checksum")).intValue()).isEqualTo(checksums[n]); }
        for(String table:ORIGINAL_TABLES) baseline.put(table,digest(table,false));
        note(Map.of("stage","baseline-confirmed","baselineSha256",baseline));
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class); }
    private void assertBounds() {
        // Expected business limit is 20; a broken last-slot race can leave 21 for investigation.
        assertThat(count("account")).isEqualTo(6); assertThat(count("customer_address")).isLessThanOrEqualTo(21); assertThat(count("cart_item")).isLessThanOrEqualTo(3);
        assertThat(count("idempotency_request")).isLessThanOrEqualTo(64); assertThat(count("audit_log")).isLessThanOrEqualTo(48);
    }
    private void assertOriginalRows() throws Exception {
        for(String table:ORIGINAL_TABLES) assertThat(digest(table,table.equals("account"))).as("Unchanged original rows: %s",table).isEqualTo(baseline.get(table));
    }
    private String digest(String table,boolean excludeFixtures) throws Exception {
        // SELECT * covers every original column, including password hashes, without logging them.
        String sql="SELECT * FROM `"+table+"`"+(excludeFixtures?" WHERE NOT(account_type='CUSTOMER' AND username IN (?,?) AND display_name=?)":"");
        List<String> rowHashes=jdbc.query(sql,rs->{
            List<String> hashes=new ArrayList<>(); int columns=rs.getMetaData().getColumnCount();
            while(rs.next()) { StringBuilder row=new StringBuilder();
                for(int c=1;c<=columns;c++) { Object value=rs.getObject(c); String text=value==null?"<null>":value instanceof byte[] bytes?Base64.getEncoder().encodeToString(bytes):value.toString(); row.append(c).append(':').append(value==null?-1:text.length()).append(':').append(text).append(';'); }
                hashes.add(WriteRequestFingerprint.sha256(row.toString()));
            } return hashes;
        },excludeFixtures?new Object[]{actors.get(0).username(),actors.get(1).username(),display}:new Object[]{});
        Collections.sort(rowHashes); return WriteRequestFingerprint.sha256(String.join("\n",rowHashes));
    }

    private void cleanupVerifiedOwnedRows() throws Exception {
        assertThat(actors).hasSize(2); assertBounds();
        String a=actors.get(0).id(),b=actors.get(1).id();
        // Validate the entire candidate set BEFORE any SQL delete. Unknown ownership or trace aborts.
        var audits=jdbc.queryForList("SELECT CAST(id AS CHAR) id,CAST(actor_id AS CHAR) actor,action,resource_type,CAST(resource_id AS CHAR) resource,trace_id FROM audit_log");
        for(var row:audits) {
            assertThat(row.get("actor").toString()).isIn(a,b); assertThat(traces).contains(row.get("trace_id").toString());
            String type=row.get("resource_type").toString(); assertThat(type).isIn("ADDRESS","CART");
            assertThat(row.get("action").toString()).isIn(type+"_CREATE",type+"_UPDATE",type+"_DELETE");
            assertThat(type.equals("ADDRESS")?addressIds:cartIds).contains(row.get("resource").toString());
        }
        var claims=jdbc.queryForList("SELECT CAST(id AS CHAR) id,actor_scope,method,route,key_hash,state FROM idempotency_request");
        for(var row:claims) {
            assertThat(row.get("state").toString()).isEqualTo("COMPLETED");
            assertThat(requests.stream().anyMatch(ref->row.get("actor_scope").equals("CUSTOMER:"+ref.actor()) && row.get("method").equals(ref.method()) && row.get("route").equals(ref.path()) && row.get("key_hash").equals(WriteRequestFingerprint.sha256(ref.key())))).isTrue();
        }
        for(String table:List.of("customer_address","cart_item")) {
            var rows=jdbc.queryForList("SELECT CAST(id AS CHAR) id,CAST(customer_id AS CHAR) owner FROM `"+table+"`");
            for(var row:rows) { assertThat(row.get("owner").toString()).isEqualTo(a); assertThat(table.equals("customer_address")?addressIds:cartIds).contains(row.get("id").toString()); }
        }
        note(Map.of("stage","exact-cleanup-intent","auditIds",audits.stream().map(r->r.get("id")).toList(),"claimIds",claims.stream().map(r->r.get("id")).toList()));
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            for(var row:audits) assertThat(jdbc.update("DELETE FROM audit_log WHERE id=CAST(? AS UNSIGNED) AND actor_id=CAST(? AS UNSIGNED) AND resource_type=? AND resource_id=CAST(? AS UNSIGNED) AND trace_id=?",row.get("id"),row.get("actor"),row.get("resource_type"),row.get("resource"),row.get("trace_id"))).isEqualTo(1);
            for(var row:claims) assertThat(jdbc.update("DELETE FROM idempotency_request WHERE id=CAST(? AS UNSIGNED) AND actor_scope=? AND method=? AND route=? AND key_hash=? AND state='COMPLETED'",row.get("id"),row.get("actor_scope"),row.get("method"),row.get("route"),row.get("key_hash"))).isEqualTo(1);
            for(String id:addressIds) assertThat(jdbc.update("DELETE FROM customer_address WHERE id=CAST(? AS UNSIGNED) AND customer_id=CAST(? AS UNSIGNED)",id,a)).isBetween(0,1);
            for(String id:cartIds) assertThat(jdbc.update("DELETE FROM cart_item WHERE id=CAST(? AS UNSIGNED) AND customer_id=CAST(? AS UNSIGNED)",id,a)).isBetween(0,1);
            for(Actor actor:actors) assertThat(jdbc.update("DELETE FROM account WHERE id=CAST(? AS UNSIGNED) AND account_type='CUSTOMER' AND username=? AND display_name=? AND enabled=1 AND version=0",actor.id(),actor.username(),display)).isEqualTo(1);
            for(String table:List.of("customer_address","cart_item","idempotency_request","audit_log")) assertThat(count(table)).isZero();
        });
    }
    private String identityBudget(String username) { return "zhicai:auth:budget:v2:identity:"+WriteRequestFingerprint.sha256("CUSTOMER:"+username); }
    private void cleanupExactIdentityBudgets() throws Exception {
        // These UUID identities had no key before login. Shared IP/admin budgets are never removed.
        var script=new DefaultRedisScript<>("""
            if redis.call('EXISTS',KEYS[1])==0 then return 0 end
            if redis.call('HLEN',KEYS[1])~=1 or redis.call('HGET',KEYS[1],'count')~='0' then return -1 end
            return redis.call('DEL',KEYS[1])
            """,Long.class);
        for(Actor actor:actors) {
            String key=identityBudget(actor.username()); note(Map.of("stage","redis-exact-cleanup-intent","identityBudgetKey",key));
            assertThat(redis.execute(script,List.of(key))).isIn(0L,1L); assertThat(redis.hasKey(key)).isFalse();
        }
    }
    private void openManifest() throws Exception {
        String root=System.getenv("M1_CUSTOMER_EVIDENCE_ROOT");
        if(root==null || root.isBlank()) throw new IllegalStateException("External M1_CUSTOMER_EVIDENCE_ROOT required before fixture writes");
        Path directory=Path.of(root).toAbsolutePath().normalize(), checkout=Path.of("").toAbsolutePath().normalize();
        if(directory.startsWith(checkout) || directory.startsWith(Path.of("."))) throw new IllegalStateException("Evidence must stay outside the checkout");
        Files.createDirectories(directory); manifest=directory.resolve("customer-http-"+run+".jsonl");
        Files.createFile(manifest); note(Map.of("stage","run-start","run",run,"maxAccounts",2,"maxAddresses",21,"maxCartRows",3,"maxClaims",64,"maxAudits",48));
    }
    private synchronized void note(Map<String,?> event) throws Exception {
        if(manifest==null) return;
        byte[] line=(json.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8);
        try(FileChannel file=FileChannel.open(manifest,StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
            ByteBuffer buffer=ByteBuffer.wrap(line); while(buffer.hasRemaining()) file.write(buffer); file.force(true);
        }
    }
}
