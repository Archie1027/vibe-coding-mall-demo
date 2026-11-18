package com.zhicai.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.common.ApiResult;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.catalog.CatalogModels.PageData;
import com.zhicai.platform.trade.AddressService;
import com.zhicai.platform.trade.CartService;
import com.zhicai.platform.trade.TradeModels.*;
import com.zhicai.platform.write.CustomerDataApplication;
import com.zhicai.platform.write.CatalogWriteExecutor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class CustomerDataController {
    private final CustomerDataApplication data;
    public CustomerDataController(CustomerDataApplication data) { this.data=data; }
    @GetMapping("/addresses")
    ApiResult<PageData<Address>> addresses(@AuthenticationPrincipal AccountView actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request) {
        paging(page,size); return ApiResult.ok(data.addresses(actor,page,size),TraceFilter.id(request));
    }
    @GetMapping("/cart")
    ApiResult<PageData<CartLine>> cart(@AuthenticationPrincipal AccountView actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request) {
        paging(page,size); return ApiResult.ok(data.cart(actor,page,size),TraceFilter.id(request));
    }
    @PostMapping("/addresses")
    ResponseEntity<String> createAddress(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key,@AuthenticationPrincipal AccountView actor,HttpServletRequest request) {
        var input=address(body,false); AddressService.validate(input);
        return response(data.saveAddress(actor,null,input,null,key,TraceFilter.id(request)));
    }
    @PutMapping("/addresses/{id}")
    ResponseEntity<String> updateAddress(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key,@AuthenticationPrincipal AccountView actor,HttpServletRequest request) {
        var input=address(body,true); long version=body.get("version").longValue(); AddressService.validateUpdate(id,input,version);
        return response(data.saveAddress(actor,id,input,version,key,TraceFilter.id(request)));
    }
    @DeleteMapping("/addresses/{id}")
    ResponseEntity<String> deleteAddress(@PathVariable String id,@RequestBody(required=false) byte[] body,@RequestHeader(value="Idempotency-Key",required=false) String key,@AuthenticationPrincipal AccountView actor,HttpServletRequest request) {
        noBody(body); return response(data.deleteAddress(actor,id,key,TraceFilter.id(request)));
    }
    @PostMapping("/cart/items")
    ResponseEntity<String> addCart(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key,@AuthenticationPrincipal AccountView actor,HttpServletRequest request) {
        if(!object(body,2) || !body.path("skuId").isTextual() || !integer(body.path("quantity"))) throw invalid();
        String sku=body.get("skuId").textValue(); int quantity=body.get("quantity").intValue(); CartService.validateAdd(sku,quantity);
        return response(data.addCart(actor,sku,quantity,key,TraceFilter.id(request)));
    }
    @PutMapping("/cart/items/{id}")
    ResponseEntity<String> updateCart(@PathVariable String id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key,@AuthenticationPrincipal AccountView actor,HttpServletRequest request) {
        if(!object(body,2) || !integer(body.path("quantity")) || !body.path("selected").isBoolean()) throw invalid();
        int quantity=body.get("quantity").intValue(); boolean selected=body.get("selected").booleanValue(); CartService.validateUpdate(id,quantity,selected);
        return response(data.updateCart(actor,id,quantity,selected,key,TraceFilter.id(request)));
    }
    @DeleteMapping("/cart/items/{id}")
    ResponseEntity<String> deleteCart(@PathVariable String id,@RequestBody(required=false) byte[] body,@RequestHeader(value="Idempotency-Key",required=false) String key,@AuthenticationPrincipal AccountView actor,HttpServletRequest request) {
        noBody(body); return response(data.deleteCart(actor,id,key,TraceFilter.id(request)));
    }
    private static AddressInput address(JsonNode body,boolean update) {
        if(!object(body,update?8:7) || !body.path("isDefault").isBoolean()) throw invalid();
        for(String name:java.util.List.of("recipient","phone","province","city","district","detail")) if(!body.path(name).isTextual()) throw invalid();
        if(update && (!body.path("version").isIntegralNumber() || !body.path("version").canConvertToLong())) throw invalid();
        return new AddressInput(body.get("recipient").textValue(),body.get("phone").textValue(),body.get("province").textValue(),body.get("city").textValue(),body.get("district").textValue(),body.get("detail").textValue(),body.get("isDefault").booleanValue());
    }
    private static boolean object(JsonNode body,int fields) { return body!=null && body.isObject() && body.size()==fields; }
    private static boolean integer(JsonNode node) { return node.isIntegralNumber() && node.canConvertToInt(); }
    private static void paging(int page,int size) { if(page<1 || size<1 || size>100) throw invalid(); }
    private static void noBody(byte[] body) { if(body!=null && body.length>0) throw invalid(); }
    private static ApiException invalid() { return new ApiException(400,"VALIDATION_ERROR","请求字段或类型不合法"); }
    private static ResponseEntity<String> response(CatalogWriteExecutor.Response result) { return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body()); }
}
