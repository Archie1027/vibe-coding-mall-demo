package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.trade.QuoteService;
import com.zhicai.platform.trade.QuoteModels.*;
import com.zhicai.platform.write.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuoteApplicationTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @SuppressWarnings("unchecked") final ObjectProvider<QuoteService> services=mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") final ObjectProvider<CatalogWriteExecutor> executors=mock(ObjectProvider.class);
    final QuoteService service=mock(QuoteService.class);
    final QuoteApplication app=new QuoteApplication(services,executors,json);
    final String key="key-12345678901234",trace=UUID.randomUUID().toString();
    final Request request=new Request("2",List.of(new Item("3",2)),null);
    AccountView actor(String id,AccountType type){return new AccountView(id,"actor","Actor",type,List.of("catalog:write"),List.of(),List.of(),true,0);}
    AccountView customer(){return actor("1",AccountType.CUSTOMER);}
    void rejected(AccountView actor,Request input,String inputKey,int status){
        assertThatThrownBy(()->app.create(actor,input,inputKey,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(status);
        verifyNoInteractions(services,executors,service);
    }
    CapturingJdbc enable(){
        var jdbc=new CapturingJdbc();var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        when(executors.getIfAvailable()).thenReturn(new CatalogWriteExecutor(jdbc,manager,json,new ReplayCipher(Base64.getEncoder().encodeToString(new byte[32])),java.time.Clock.systemUTC(),AccountType.CUSTOMER));
        return jdbc;
    }
    @Test void authenticationAndCustomerTypeAreRequiredBeforeProviders(){
        rejected(null,request,key,401);rejected(actor("1",AccountType.STAFF),request,key,403);
    }
    @Test void disabledWritesReturn503WithoutResolvingService(){
        assertThatThrownBy(()->app.create(customer(),request,key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);
        verify(executors).getIfAvailable();verifyNoInteractions(services,service);
    }
    @Test void missingServiceReturns503WithoutCallingExecutor(){
        var jdbc=enable();
        assertThatThrownBy(()->app.create(customer(),request,key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);
        assertThat(jdbc.owner).isNull();
    }
    @Test void typedInputAndMergedQuantitiesAreValidatedBeforeProviders(){
        for(Request input:Arrays.asList(null,new Request(null,request.items(),null),new Request("2",null,null),new Request("2",List.of(),null),
            new Request("2",Collections.nCopies(101,new Item("3",1)),null),new Request("2",Arrays.asList((Item)null),null),
            new Request("2",List.of(new Item("3",null)),null),new Request("2",List.of(new Item("3",0)),null),new Request("2",List.of(new Item("3",100)),null),
            new Request("2",List.of(new Item("3",50),new Item("3",50)),null)))rejected(customer(),input,key,400);
    }
    @Test void contractIdsAreRequiredForOwnerAddressSkuAndCoupon(){
        for(String id:List.of("0","01","-1","1.0","99999999999999999999")){
            rejected(actor(id,AccountType.CUSTOMER),request,key,400);
            rejected(customer(),new Request(id,request.items(),null),key,400);
            rejected(customer(),new Request("2",List.of(new Item(id,1)),null),key,400);
            rejected(customer(),new Request("2",request.items(),id),key,400);
        }
    }
    @Test void invalidKeysNeverResolveWriteDependencies(){
        for(String invalid:Arrays.asList(null,"short","x".repeat(129),"with space-123456789", "非ASCII-12345678901234"))rejected(customer(),request,invalid,400);
    }
    @Test void delegatesExactUnmergedFingerprintAndOnlyAuditsQuoteId()throws Exception{
        var jdbc=enable();when(services.getIfAvailable()).thenReturn(service);
        var input=new Request("2",List.of(new Item("3",1),new Item("3",2)),null);
        var quote=new Quote("7","8",List.of(new Line("3","PRIVATE-CATALOG",List.of(),3,"100","300","0","300")),"300","0","0","300",null,Instant.parse("2026-10-25T00:05:00Z"));
        when(service.create("1",input)).thenReturn(quote);
        var response=app.create(customer(),input,key,trace);
        assertThat(response.status()).isEqualTo(201);assertThat(jdbc.actor).isEqualTo("CUSTOMER:1");assertThat(jdbc.method).isEqualTo("POST");assertThat(jdbc.route).isEqualTo("/api/v1/checkout/quotes");
        assertThat(jdbc.hash).isEqualTo(new WriteRequestFingerprint(json).requestHash("CUSTOMER:1","POST","/api/v1/checkout/quotes",json.valueToTree(input)));
        assertThat(jdbc.keyHash).isEqualTo(new WriteRequestFingerprint(json).keyHash(key));
        assertThat(json.readTree(response.body()).path("data")).isEqualTo(json.valueToTree(quote));assertThat(json.readTree(response.body()).path("traceId").asText()).isEqualTo(trace);
        assertThat(jdbc.action).isEqualTo("QUOTE_CREATE");assertThat(jdbc.resource).isEqualTo("QUOTE");assertThat(jdbc.resourceId).isEqualTo("7");
        assertThat(json.readTree(jdbc.audit)).isEqualTo(json.readTree("{\"before\":null,\"after\":{\"id\":\"7\"}}"));
        assertThat(app.create(customer(),input,key,UUID.randomUUID().toString()).body()).isEqualTo(response.body());verify(service,times(1)).create("1",input);
    }
    @Test void couponIsPreservedAndDependencyErrorsPropagate(){
        var jdbc=enable();when(services.getIfAvailable()).thenReturn(service);
        var input=new Request("2",request.items(),"4");var unavailable=new ApiException(503,"DEPENDENCY_UNAVAILABLE","coupon unavailable");
        when(service.create("1",input)).thenThrow(unavailable);
        assertThatThrownBy(()->app.create(customer(),input,key,trace)).isSameAs(unavailable);
        assertThat(jdbc.hash).isEqualTo(new WriteRequestFingerprint(json).requestHash("CUSTOMER:1","POST","/api/v1/checkout/quotes",json.valueToTree(input)));
        assertThat(jdbc.audit).isNull();assertThat(jdbc.state).isEqualTo("IN_PROGRESS");verify(service).create("1",input);
    }
    static class CapturingJdbc extends CustomerWriteExecutorTest.MemoryJdbc {
        String method,route,keyHash,action,resource,resourceId;
        @Override public int update(String sql,Object... args){
            if(sql.startsWith("INSERT INTO idempotency_request")){method=(String)args[1];route=(String)args[2];keyHash=(String)args[3];}
            if(sql.startsWith("INSERT INTO audit_log")){action=(String)args[1];resource=(String)args[2];resourceId=(String)args[3];}
            return super.update(sql,args);
        }
    }
}
