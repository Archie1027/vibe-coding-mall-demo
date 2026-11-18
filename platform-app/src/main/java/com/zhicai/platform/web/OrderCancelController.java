package com.zhicai.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.write.OrderCancelApplication;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderCancelController {
    private final OrderCancelApplication orders;
    public OrderCancelController(OrderCancelApplication orders){this.orders=orders;}
    @PostMapping("/api/v1/orders/{id}/cancel")
    ResponseEntity<String> cancel(@PathVariable String id,@RequestBody JsonNode body,
        @RequestHeader(value="Idempotency-Key",required=false) String key,@AuthenticationPrincipal AccountView actor,HttpServletRequest request){
        if(body==null || !body.isObject() || body.size()!=1 || !body.path("reason").isTextual())
            throw new ApiException(400,"VALIDATION_ERROR","取消请求只能包含字符串reason");
        var result=orders.cancel(actor,id,body.get("reason").textValue(),key,TraceFilter.id(request));
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}
