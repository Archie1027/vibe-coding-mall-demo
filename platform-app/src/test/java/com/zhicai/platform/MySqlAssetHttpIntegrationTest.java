package com.zhicai.platform;

import com.fasterxml.jackson.databind.*;
import com.zhicai.platform.catalog.*;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.media.*;
import com.zhicai.platform.write.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="M1_ASSET_MYSQL_INTEGRATION",matches="approved-temporary-assets")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.flyway.enabled=false","app.catalog.upload-root=${M1_ASSET_TEST_ROOT}"})
class MySqlAssetHttpIntegrationTest {
 @LocalServerPort int port;
 @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired PlatformTransactionManager transactions;
 @Autowired AssetWriteMapper mapper;@Autowired AssetFileStore files;@Autowired ImageUploadValidator validator;@Autowired @org.springframework.beans.factory.annotation.Qualifier("catalogWriteExecutor") ObjectProvider<CatalogWriteExecutor> executors;
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 final Map<String,List<Map<String,Object>>> baseline=new LinkedHashMap<>();
 final Map<String,OwnedAsset> owned=new LinkedHashMap<>();final Set<RequestRef> requests=new HashSet<>();final Set<String> traces=new HashSet<>();
 String token,actor;Path root;boolean unknown;
 record OwnedAsset(String key,String type,byte[] bytes){} record RequestRef(String route,String key){} record Reply(int status,String body,JsonNode json){}
 @BeforeEach void before()throws Exception{
  assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset",Integer.class)).isEqualTo(120);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product",Integer.class)).isEqualTo(1000);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class)).isZero();
  for(String table:List.of("asset","category","brand","product","sku","product_asset","flyway_schema_history"))baseline.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1,2"));
  root=Path.of(System.getenv("M1_ASSET_TEST_ROOT")).toAbsolutePath().normalize();assertThat(root.toString()).startsWith("K:");
  if(Files.exists(root))try(var paths=Files.list(root)){assertThat(paths.count()).isZero();}
  String password=System.getenv("M1_ASSET_ADMIN_PASSWORD");if(password==null)throw new IllegalStateException("Scoped credential missing");
  Reply login=send(HttpRequest.newBuilder(uri("/api/v1/auth/staff/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username","admin","password",password)))).build());
  assertThat(login.status()).isEqualTo(200);token=login.json().at("/data/accessToken").asText();
  actor=send(HttpRequest.newBuilder(uri("/api/v1/me")).header("Authorization","Bearer "+token).GET().build()).json().at("/data/id").asText();assertThat(actor).matches("[1-9][0-9]{0,18}");
 }
 @Test void actualFormatsReplayValidationPublicReadAndRollbackCleanup()throws Exception{
  byte[] png=ImageUploadValidatorTest.image("png"),jpeg=ImageUploadValidatorTest.image("jpeg"),webp;
  try(var stream=getClass().getResourceAsStream("/assets/tiny.webp")){webp=Objects.requireNonNull(stream).readAllBytes();}
  String key=UUID.randomUUID().toString();Reply first=upload(png,"image/png","../../ignored-name.png",key,token,false);assertThat(first.status()).isEqualTo(201);
  assertThat(upload(png,"image/png","different-name.png",key,token,false).body()).isEqualTo(first.body());
  Reply conflict=upload(jpeg,"image/jpeg","other.jpg",key,token,false);assertThat(conflict.status()).isEqualTo(409);assertThat(conflict.json().path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
  assertThat(upload(jpeg,"image/jpeg","image.jpg",UUID.randomUUID().toString(),token,false).status()).isEqualTo(201);
  assertThat(upload(webp,"image/webp","image.webp",UUID.randomUUID().toString(),token,false).status()).isEqualTo(201);
  assertThat(upload(png,"image/jpeg","fake.jpg",UUID.randomUUID().toString(),token,false).status()).isEqualTo(400);
  assertThat(upload("<script>bad</script>".getBytes(),"image/png","fake.png",UUID.randomUUID().toString(),token,false).status()).isEqualTo(400);
  assertThat(upload(Arrays.copyOf(jpeg,jpeg.length-2),"image/jpeg","truncated.jpg",UUID.randomUUID().toString(),token,false).status()).isEqualTo(400);
  assertThat(upload(new byte[0],"image/png","empty.png",UUID.randomUUID().toString(),token,false).status()).isEqualTo(400);
  assertThat(upload(new byte[5*1024*1024+1],"image/png","huge.png",UUID.randomUUID().toString(),token,false).status()).isEqualTo(413);
  assertThat(upload(png,"image/png","part.png",UUID.randomUUID().toString(),token,true).status()).isEqualTo(400);
  assertThat(upload(png,"image/png","part.png",null,token,false).status()).isEqualTo(400);
  assertThat(upload(png,"image/png","part.png",UUID.randomUUID().toString(),null,false).status()).isEqualTo(401);
  String original=jdbc.queryForObject("SELECT storage_key FROM asset ORDER BY id LIMIT 1",String.class);
  assertThat(http.send(HttpRequest.newBuilder(uri("/assets/products/"+original)).GET().build(),HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(200);
  var expected=List.of(png,jpeg,webp);int n=0;
  for(var asset:owned.values()){
   var reply=http.send(HttpRequest.newBuilder(uri("/assets/products/"+asset.key())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
   assertThat(reply.statusCode()).isEqualTo(200);assertThat(reply.headers().firstValue("Content-Type").orElseThrow()).isEqualTo(asset.type());assertThat(reply.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");assertThat(reply.body()).containsExactly(expected.get(n++));
  }
  // Fault injection uses the real executor/transaction/files, before asset INSERT; no schema or trigger is changed.
  AssetWriteMapper reject=new AssetWriteMapper(){public int insert(NewAsset row){throw new ApiException(409,"TEST_ROLLBACK","approved rollback test");}public PublicAsset publicByKey(String key){return mapper.publicByKey(key);}};
  var app=new AssetUploadApplication(new AssetService(reject),files,validator,executors,json,"http://127.0.0.1:8080/assets/products/");
  var staff=new AccountView(actor,"admin","Admin",AccountType.STAFF,List.of("catalog:write"),List.of(),List.of(),true,0);
  String rollbackKey=UUID.randomUUID().toString(),trace=UUID.randomUUID().toString();requests.add(new RequestRef("/api/v1/admin/assets/images",rollbackKey));traces.add(trace);
  List<String> before;try(var paths=Files.list(root)){before=paths.map(p->p.getFileName().toString()).sorted().toList();}
  var rejected=app.upload(staff,png,"image/png",rollbackKey,trace);assertThat(rejected.status()).isEqualTo(409);
  try(var paths=Files.list(root)){assertThat(paths.map(p->p.getFileName().toString()).sorted().toList()).isEqualTo(before);}
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset",Integer.class)).isEqualTo(123);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class)).isEqualTo(3);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type='ASSET' AND action='ASSET_CREATE' AND JSON_UNQUOTE(JSON_EXTRACT(masked_before_after,'$.after.fileSha256')) REGEXP '^[0-9a-f]{64}$'",Integer.class)).isEqualTo(3);
 }
 @AfterEach void cleanup()throws Exception{
  if(token==null||baseline.isEmpty())return;if(unknown)throw new IllegalStateException("Unknown upload result; preserve "+root);
  new TransactionTemplate(transactions).executeWithoutResult(status->{
   for(var entry:owned.entrySet()){
    var rows=jdbc.queryForList("SELECT storage_key,content_type,size_bytes,CAST(owner_id AS CHAR) owner FROM asset WHERE id=CAST(? AS UNSIGNED) FOR UPDATE",entry.getKey());assertThat(rows).hasSize(1);var row=rows.getFirst();
    assertThat(row.get("storage_key")).isEqualTo(entry.getValue().key());assertThat(row.get("owner")).isEqualTo(actor);assertThat(row.get("content_type")).isEqualTo(entry.getValue().type());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_asset WHERE asset_id=CAST(? AS UNSIGNED)",Integer.class,entry.getKey())).isZero();
   }
   var audits=jdbc.queryForList("SELECT CAST(id AS CHAR) id,CAST(actor_id AS CHAR) actor,CAST(resource_id AS CHAR) resource,resource_type,action,trace_id FROM audit_log");
   var claims=jdbc.queryForList("SELECT CAST(id AS CHAR) id,actor_scope,method,route,key_hash,state FROM idempotency_request");
   for(var row:audits){assertThat(row.get("actor")).isEqualTo(actor);assertThat(row.get("resource_type")).isEqualTo("ASSET");assertThat(row.get("action")).isEqualTo("ASSET_CREATE");assertThat(owned).containsKey(row.get("resource").toString());assertThat(traces).contains(row.get("trace_id").toString());}
   for(var row:claims){assertThat(row.get("actor_scope")).isEqualTo("STAFF:"+actor);assertThat(row.get("state")).isEqualTo("COMPLETED");assertThat(row.get("method")).isEqualTo("POST");assertThat(requests.stream().anyMatch(ref->ref.route().equals(row.get("route"))&&WriteRequestFingerprint.sha256(ref.key()).equals(row.get("key_hash")))).isTrue();}
   for(var row:audits)assertThat(jdbc.update("DELETE FROM audit_log WHERE id=CAST(? AS UNSIGNED) AND actor_id=CAST(? AS UNSIGNED) AND resource_type='ASSET' AND resource_id=CAST(? AS UNSIGNED) AND trace_id=?",row.get("id"),actor,row.get("resource"),row.get("trace_id"))).isEqualTo(1);
   for(var row:claims)assertThat(jdbc.update("DELETE FROM idempotency_request WHERE id=CAST(? AS UNSIGNED) AND actor_scope=? AND method='POST' AND route=? AND key_hash=?",row.get("id"),"STAFF:"+actor,row.get("route"),row.get("key_hash"))).isEqualTo(1);
   for(var entry:owned.entrySet())assertThat(jdbc.update("DELETE FROM asset WHERE id=CAST(? AS UNSIGNED) AND storage_key=? AND owner_id=CAST(? AS UNSIGNED)",entry.getKey(),entry.getValue().key(),actor)).isEqualTo(1);
   for(var entry:baseline.entrySet())assertThat(jdbc.queryForList("SELECT * FROM "+entry.getKey()+" ORDER BY 1,2")).as(entry.getKey()).isEqualTo(entry.getValue());
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class)).isZero();
   System.out.printf("ASSET_TEST_CLEANUP assets=%d claims=%d audits=%d root=%s%n",owned.size(),claims.size(),audits.size(),root.getFileName());
  });
  for(var asset:owned.values()){
   assertThat(http.send(HttpRequest.newBuilder(uri("/assets/products/"+asset.key())).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
   assertThat(Files.readAllBytes(root.resolve(asset.key()))).containsExactly(asset.bytes());files.remove(asset.key());
  }
  try(var paths=Files.list(root)){assertThat(paths.count()).isZero();}
 }
 Reply upload(byte[] bytes,String type,String filename,String key,String bearer,boolean extra)throws Exception{
  String boundary="ZC"+UUID.randomUUID().toString().replace("-","");var data=new ByteArrayOutputStream();data.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));data.write(bytes);data.write("\r\n".getBytes());
  if(extra)data.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"unexpected\"\r\n\r\nx\r\n").getBytes());data.write(("--"+boundary+"--\r\n").getBytes());
  var request=HttpRequest.newBuilder(uri("/api/v1/admin/assets/images")).timeout(Duration.ofSeconds(10)).header("Content-Type","multipart/form-data; boundary="+boundary);
  if(key!=null){request.header("Idempotency-Key",key);if(Objects.equals(bearer,token))requests.add(new RequestRef("/api/v1/admin/assets/images",key));}if(bearer!=null)request.header("Authorization","Bearer "+bearer);
  Reply reply=send(request.POST(HttpRequest.BodyPublishers.ofByteArray(data.toByteArray())).build());
  if(reply.status()==201){String id=reply.json().at("/data/id").asText(),storage=Path.of(URI.create(reply.json().at("/data/url").asText()).getPath()).getFileName().toString();assertThat(storage).matches("upload-[0-9a-f]{32}\\.(jpg|png|webp)");owned.put(id,new OwnedAsset(storage,reply.json().at("/data/contentType").asText(),bytes));}
  return reply;
 }
 Reply send(HttpRequest request)throws Exception{try{var r=http.send(request,HttpResponse.BodyHandlers.ofString());if(r.statusCode()>=500)unknown=true;JsonNode body=json.readTree(r.body());if(body.hasNonNull("traceId"))traces.add(body.path("traceId").asText());return new Reply(r.statusCode(),r.body(),body);}catch(Exception error){unknown=true;throw error;}}
 URI uri(String route){return URI.create("http://127.0.0.1:"+port+route);}
}
