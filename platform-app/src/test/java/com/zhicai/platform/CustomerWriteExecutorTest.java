package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.identity.AccountType;
import com.zhicai.platform.write.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerWriteExecutorTest {
 final ObjectMapper json=new ObjectMapper();final Clock clock=Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"),ZoneOffset.UTC);
 final ReplayCipher cipher=new ReplayCipher(Base64.getEncoder().encodeToString(new byte[32]));
 CatalogWriteExecutor executor(MemoryJdbc jdbc,AccountType type){var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());return new CatalogWriteExecutor(jdbc,manager,json,cipher,clock,type);}
 @Test void customerScopeIsSeparateAndAuditContainsNoAddressPii()throws Exception{
  var jdbc=new MemoryJdbc();var writer=executor(jdbc,AccountType.CUSTOMER);
  var data=json.createObjectNode().put("id","7").put("recipient","测试收件人").put("phone","10000000007").put("province","演示省").put("city","演示市").put("district","演示区").put("detail","演示街道1号").put("isDefault",true).put("version",0);
  var request=new CatalogWriteExecutor.Request("1","POST","/api/v1/me/addresses",UUID.randomUUID().toString(),data,UUID.randomUUID().toString());
  var response=writer.execute(request,()->new CatalogWriteExecutor.Outcome(201,data,new CatalogWriteExecutor.Audit("ADDRESS_CREATE","ADDRESS","7",null,data)));
  assertThat(jdbc.actor).isEqualTo("CUSTOMER:1");assertThat(response.status()).isEqualTo(201);
  assertThat(jdbc.audit).contains("isDefault","version").doesNotContain("recipient","phone","province","city","district","detail","10000000007","测试收件人");
  assertThat(new String(jdbc.encrypted,java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("测试收件人","10000000007");
  assertThat(writer.execute(request,()->{throw new AssertionError("Replay must not execute business operation");}).body()).isEqualTo(response.body());
 }
 @Test void customerWriterCannotAuditStaffCatalogResourcesAndViceVersa(){
  for(var type:AccountType.values()){
   var jdbc=new MemoryJdbc();var writer=executor(jdbc,type);String resource=type==AccountType.CUSTOMER?"BRAND":"ADDRESS";
   var data=json.createObjectNode().put("id","7");var request=new CatalogWriteExecutor.Request("1","POST","/api/v1/test",UUID.randomUUID().toString(),data,UUID.randomUUID().toString());
   assertThatThrownBy(()->writer.execute(request,()->new CatalogWriteExecutor.Outcome(201,data,new CatalogWriteExecutor.Audit(resource+"_CREATE",resource,"7",null,data)))).isInstanceOf(IllegalArgumentException.class);
   assertThat(jdbc.audit).isNull();assertThat(jdbc.actor).startsWith(type.name()+":");
  }
 }
 static class MemoryJdbc extends JdbcTemplate {
  String actor,hash,owner,state,audit;Integer status;byte[] encrypted;Instant lease,expires;
  @Override public int update(String sql,Object... args){
   if(sql.startsWith("INSERT INTO idempotency_request")){actor=(String)args[0];hash=(String)args[4];owner=(String)args[5];state="IN_PROGRESS";lease=((Timestamp)args[6]).toInstant();expires=((Timestamp)args[7]).toInstant();}
   else if(sql.startsWith("INSERT INTO audit_log"))audit=(String)args[4];
   else if(sql.startsWith("UPDATE idempotency_request")){status=(Integer)args[0];encrypted=(byte[])args[1];expires=((Timestamp)args[3]).toInstant();state="COMPLETED";}
   else throw new AssertionError(sql);return 1;
  }
  @Override public <T>T queryForObject(String sql,RowMapper<T> mapper,Object... args){
   if(owner==null)throw new EmptyResultDataAccessException(1);
   try{var rs=mock(ResultSet.class);when(rs.getString("id")).thenReturn("9");when(rs.getString("request_hash")).thenReturn(hash);when(rs.getString("owner_token")).thenReturn(owner);when(rs.getString("state")).thenReturn(state);when(rs.getObject("http_status")).thenReturn(status);when(rs.getBytes("encrypted_response")).thenReturn(encrypted);when(rs.getTimestamp("lease_until")).thenReturn(Timestamp.from(lease));when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(expires));return mapper.mapRow(rs,0);}catch(SQLException e){throw new IllegalStateException(e);}
  }
 }
}
