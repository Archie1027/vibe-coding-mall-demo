package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountType;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.trade.QuoteModels.Request;
import com.zhicai.platform.trade.QuoteService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Customer authentication and validation precede any idempotency or business SQL. */
@Service
public class QuoteApplication {
    private final ObjectProvider<QuoteService> quotes;
    private final ObjectProvider<CatalogWriteExecutor> executors;
    private final ObjectMapper json;
    public QuoteApplication(ObjectProvider<QuoteService> quotes,
        @Qualifier("customerWriteExecutor") ObjectProvider<CatalogWriteExecutor> executors,ObjectMapper json) {
        this.quotes=quotes;this.executors=executors;this.json=json;
    }
    public CatalogWriteExecutor.Response create(AccountView actor,Request request,String key,String trace) {
        if(actor==null)throw ApiException.unauthenticated();
        if(actor.accountType()!=AccountType.CUSTOMER)throw new ApiException(403,"FORBIDDEN","仅顾客可以生成本人报价");
        QuoteService.validate(actor.id(),request);
        new WriteRequestFingerprint(json).keyHash(key);
        var executor=executors.getIfAvailable();
        if(executor==null)throw unavailable();
        var service=quotes.getIfAvailable();
        if(service==null)throw unavailable();
        return executor.execute(new CatalogWriteExecutor.Request(actor.id(),"POST","/api/v1/checkout/quotes",key,json.valueToTree(request),trace),()->{
            var quote=service.create(actor.id(),request);
            return new CatalogWriteExecutor.Outcome(201,quote,new CatalogWriteExecutor.Audit("QUOTE_CREATE","QUOTE",quote.id(),null,json.createObjectNode().put("id",quote.id())));
        });
    }
    private static ApiException unavailable(){return new ApiException(503,"DEPENDENCY_UNAVAILABLE","报价写入功能尚未启用");}
}
