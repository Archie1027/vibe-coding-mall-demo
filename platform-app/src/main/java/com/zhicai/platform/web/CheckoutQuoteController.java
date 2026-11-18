package com.zhicai.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.trade.QuoteModels.Item;
import com.zhicai.platform.trade.QuoteModels.Request;
import com.zhicai.platform.trade.QuoteService;
import com.zhicai.platform.write.QuoteApplication;
import com.zhicai.platform.write.WriteRequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/v1/checkout/quotes")
public class CheckoutQuoteController {
    private final QuoteApplication quotes;
    private final ObjectMapper json;
    public CheckoutQuoteController(QuoteApplication quotes,ObjectMapper json){this.quotes=quotes;this.json=json;}
    @PostMapping
    ResponseEntity<String> create(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key,
        @AuthenticationPrincipal AccountView actor,HttpServletRequest request){
        var input=input(body);
        QuoteService.validate(actor.id(),input);
        new WriteRequestFingerprint(json).keyHash(key);
        var result=quotes.create(actor,input,key,TraceFilter.id(request));
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
    private static Request input(JsonNode body){
        if(body==null || !body.isObject() || body.size()!=(body.has("couponId")?3:2)
            || !body.path("addressId").isTextual() || !body.path("items").isArray()
            || body.path("items").size()<1 || body.path("items").size()>100
            || (body.has("couponId") && !body.path("couponId").isTextual()))throw invalid();
        var items=new ArrayList<Item>();
        for(var item:body.get("items")){
            if(!item.isObject() || item.size()!=2 || !item.path("skuId").isTextual()
                || !item.path("quantity").isIntegralNumber() || !item.path("quantity").canConvertToInt())throw invalid();
            items.add(new Item(item.get("skuId").textValue(),item.get("quantity").intValue()));
        }
        return new Request(body.get("addressId").textValue(),items,body.has("couponId")?body.get("couponId").textValue():null);
    }
    private static ApiException invalid(){return new ApiException(400,"VALIDATION_ERROR","请求字段或类型不合法");}
}
