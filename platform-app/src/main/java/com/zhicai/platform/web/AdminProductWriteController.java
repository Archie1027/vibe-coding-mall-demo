package com.zhicai.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhicai.platform.catalog.ProductWriteService;
import com.zhicai.platform.catalog.CatalogModels.Spec;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.write.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminProductWriteController {
    private final ProductWriteApplication writes;
    public AdminProductWriteController(ProductWriteApplication writes) { this.writes = writes; }
    @PostMapping("/products")
    ResponseEntity<String> create(@RequestBody JsonNode body, @RequestHeader(value="Idempotency-Key", required=false) String key,
        @AuthenticationPrincipal AccountView actor, HttpServletRequest request) { return product(null, body, key, actor, request); }
    @PutMapping("/products/{id}")
    ResponseEntity<String> update(@PathVariable String id, @RequestBody JsonNode body, @RequestHeader(value="Idempotency-Key", required=false) String key,
        @AuthenticationPrincipal AccountView actor, HttpServletRequest request) { return product(id, body, key, actor, request); }
    @PostMapping("/products/{id}/state")
    ResponseEntity<String> state(@PathVariable String id, @RequestBody JsonNode body, @RequestHeader(value="Idempotency-Key", required=false) String key,
        @AuthenticationPrincipal AccountView actor, HttpServletRequest request) {
        fields(body, Set.of("state", "version")); String state = text(body, "state"); Long version = version(body);
        ProductWriteService.stateRequest(id, state, version);
        return response(writes.state(actor, id, state, version, key, TraceFilter.id(request)));
    }
    @PostMapping("/products/{id}/skus")
    ResponseEntity<String> createSku(@PathVariable String id, @RequestBody JsonNode body, @RequestHeader(value="Idempotency-Key", required=false) String key,
        @AuthenticationPrincipal AccountView actor, HttpServletRequest request) { return sku(id, null, body, key, actor, request); }
    @PutMapping("/skus/{id}")
    ResponseEntity<String> updateSku(@PathVariable String id, @RequestBody JsonNode body, @RequestHeader(value="Idempotency-Key", required=false) String key,
        @AuthenticationPrincipal AccountView actor, HttpServletRequest request) { return sku(null, id, body, key, actor, request); }
    private ResponseEntity<String> product(String id, JsonNode body, String key, AccountView actor, HttpServletRequest request) {
        fields(body, id == null ? Set.of("name","categoryId","brandId","imageAssetIds","description","featured") : Set.of("name","categoryId","brandId","imageAssetIds","description","featured","version"));
        if (!body.path("imageAssetIds").isArray()) bad();
        List<String> ids = new ArrayList<>(); for (JsonNode value : body.path("imageAssetIds")) { if (!value.isTextual()) bad(); ids.add(value.textValue()); }
        var input = new ProductWriteService.Input(text(body,"name"), text(body,"categoryId"), text(body,"brandId"), ids, text(body,"description"), bool(body,"featured"));
        Long version = id == null ? null : version(body); ProductWriteService.validate(input); if (id != null) ProductWriteService.identityVersion(id,version);
        return response(writes.saveProduct(actor,id,input,version,key,TraceFilter.id(request)));
    }
    private ResponseEntity<String> sku(String productId, String id, JsonNode body, String key, AccountView actor, HttpServletRequest request) {
        fields(body, id == null ? Set.of("code","specs","priceMinor","enabled") : Set.of("code","specs","priceMinor","enabled","version"));
        if (!body.path("specs").isArray()) bad(); List<Spec> specs = new ArrayList<>();
        for (JsonNode value : body.path("specs")) { fields(value,Set.of("name","value")); specs.add(new Spec(text(value,"name"),text(value,"value"))); }
        var input = new ProductWriteService.SkuInput(text(body,"code"),specs,text(body,"priceMinor"),bool(body,"enabled"));
        Long version = id == null ? null : version(body); ProductWriteService.validate(input); ProductWriteService.identityVersion(id == null ? productId : id,id == null ? 0L : version);
        return response(writes.saveSku(actor,productId,id,input,version,key,TraceFilter.id(request)));
    }
    private void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject() || body.size() != allowed.size()) bad();
        body.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) bad(); });
    }
    private String text(JsonNode body, String key) { if (!body.path(key).isTextual()) bad(); return body.path(key).textValue(); }
    private Boolean bool(JsonNode body, String key) { if (!body.path(key).isBoolean()) bad(); return body.path(key).booleanValue(); }
    private Long version(JsonNode body) { if (!body.path("version").isIntegralNumber() || !body.path("version").canConvertToLong()) bad(); return body.path("version").longValue(); }
    private ResponseEntity<String> response(CatalogWriteExecutor.Response result) { return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body()); }
    private void bad() { throw new ApiException(400,"VALIDATION_ERROR","商品或SKU请求字段或类型不合法"); }
}
