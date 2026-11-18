package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.identity.AccountType;
import com.zhicai.platform.write.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class QuoteAuditExecutorTest {
    final ObjectMapper json=new ObjectMapper();
    final CustomerWriteExecutorTest helper=new CustomerWriteExecutorTest();
    CatalogWriteExecutor.Request request(){return new CatalogWriteExecutor.Request("1","POST","/api/v1/checkout/quotes",UUID.randomUUID().toString(),json.createObjectNode().put("addressId","2"),UUID.randomUUID().toString());}
    @Test void quoteCreateHasOnlyIdAuditAndEncryptedByteExactReplay()throws Exception{
        var jdbc=new CustomerWriteExecutorTest.MemoryJdbc();var executor=helper.executor(jdbc,AccountType.CUSTOMER);
        var data=json.createObjectNode().put("id","7").put("recipient","PRIVATE-RECIPIENT").put("phone","10000000007").put("addressId","2").put("couponId","4").put("productName","PRIVATE-CATALOG").put("quantity",2).put("priceMinor","200").put("version",5);
        var request=request();var response=executor.execute(request,()->new CatalogWriteExecutor.Outcome(201,data,new CatalogWriteExecutor.Audit("QUOTE_CREATE","QUOTE","7",null,data)));
        assertThat(response.status()).isEqualTo(201);assertThat(jdbc.actor).isEqualTo("CUSTOMER:1");
        assertThat(json.readTree(jdbc.audit)).isEqualTo(json.readTree("{\"before\":null,\"after\":{\"id\":\"7\"}}"));
        assertThat(new String(jdbc.encrypted,StandardCharsets.UTF_8)).doesNotContain("PRIVATE-RECIPIENT","10000000007","PRIVATE-CATALOG");
        assertThat(executor.execute(request,()->{throw new AssertionError("Must replay without executing business operation");}).body()).isEqualTo(response.body());
    }
    @Test void quoteUpdateAndDeleteAreNotAllowed(){
        for(String action:List.of("QUOTE_UPDATE","QUOTE_DELETE")){
            var jdbc=new CustomerWriteExecutorTest.MemoryJdbc();var executor=helper.executor(jdbc,AccountType.CUSTOMER);
            assertThatThrownBy(()->executor.execute(request(),()->new CatalogWriteExecutor.Outcome(201,json.createObjectNode(),new CatalogWriteExecutor.Audit(action,"QUOTE","7",null,json.createObjectNode().put("id","7")))))
                .isInstanceOf(IllegalArgumentException.class);assertThat(jdbc.audit).isNull();
        }
    }
    @Test void staffCannotAuditCustomerQuotes(){
        var jdbc=new CustomerWriteExecutorTest.MemoryJdbc();var executor=helper.executor(jdbc,AccountType.STAFF);
        assertThatThrownBy(()->executor.execute(request(),()->new CatalogWriteExecutor.Outcome(201,json.createObjectNode(),new CatalogWriteExecutor.Audit("QUOTE_CREATE","QUOTE","7",null,json.createObjectNode().put("id","7")))))
            .isInstanceOf(IllegalArgumentException.class);assertThat(jdbc.audit).isNull();
    }
}
