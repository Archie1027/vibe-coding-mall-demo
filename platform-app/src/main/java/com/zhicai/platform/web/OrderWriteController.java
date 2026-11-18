package com.zhicai.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.trade.OrderModels;
import com.zhicai.platform.write.OrderApplication;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderWriteController {
    private final OrderApplication orders;
    public OrderWriteController(OrderApplication orders){this.orders=orders;}
    @PostMapping
    ResponseEntity<String> create(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key,
        @AuthenticationPrincipal AccountView actor,HttpServletRequest request){
        if(body==null || !body.isObject() || body.size()!=(body.has("remark")?2:1)
            || !body.path("quoteId").isTextual() || (body.has("remark") && !body.path("remark").isTextual()))
            throw new ApiException(400,"VALIDATION_ERROR","订单请求字段不合法");
        var input=new OrderModels.Create(body.get("quoteId").textValue(),body.has("remark")?body.get("remark").textValue():null);
        var result=orders.create(actor,input,key,TraceFilter.id(request));
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}
