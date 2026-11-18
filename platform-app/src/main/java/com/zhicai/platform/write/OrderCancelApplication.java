package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.trade.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class OrderCancelApplication {
    private final ObjectProvider<OrderClosingService> services;
    private final ObjectProvider<CatalogWriteExecutor> executors;
    private final OrderReadService reads;
    private final ObjectMapper json;
    public OrderCancelApplication(ObjectProvider<OrderClosingService> services,
        @Qualifier("customerWriteExecutor") ObjectProvider<CatalogWriteExecutor> executors,OrderReadService reads,ObjectMapper json){
        this.services=services;this.executors=executors;this.reads=reads;this.json=json;
    }
    public CatalogWriteExecutor.Response cancel(AccountView actor,String id,String reason,String key,String trace){
        if(actor==null)throw ApiException.unauthenticated();
        if(actor.accountType()!=AccountType.CUSTOMER)throw new ApiException(403,"FORBIDDEN","仅顾客可以取消本人订单");
        OrderClosingService.validateCancel(actor.id(),id,reason,trace);new WriteRequestFingerprint(json).keyHash(key);
        var executor=executors.getIfAvailable();if(executor==null)throw unavailable();
        var service=services.getIfAvailable();if(service==null)throw unavailable();
        var input=json.createObjectNode().put("reason",reason);
        return executor.execute(new CatalogWriteExecutor.Request(actor.id(),"POST","/api/v1/orders/"+id+"/cancel",key,input,trace),()->{
            var result=service.cancel(actor.id(),id,reason,trace);
            var order=reads.find(actor.id(),id);
            if(!result.id().equals(order.id()) || !result.state().equals(order.state()) || result.version()!=order.version())
                throw new IllegalStateException("Cancelled order snapshot differs inside transaction");
            var before=json.createObjectNode().put("id",id).put("state","UNPAID"); // Successful cancel proves this prior state.
            var after=json.createObjectNode().put("id",id).put("state",result.state());
            return new CatalogWriteExecutor.Outcome(200,order,new CatalogWriteExecutor.Audit("ORDER_CANCEL","ORDER",id,before,after));
        });
    }
    private static ApiException unavailable(){return new ApiException(503,"DEPENDENCY_UNAVAILABLE","订单取消功能尚未启用");}
}
