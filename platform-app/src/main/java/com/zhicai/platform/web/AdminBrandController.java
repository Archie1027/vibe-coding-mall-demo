package com.zhicai.platform.web;

import com.zhicai.platform.catalog.BrandWriteService;
import com.zhicai.platform.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.write.BrandWriteApplication;
import com.zhicai.platform.write.CatalogWriteExecutor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/brands")
public class AdminBrandController {
    private final BrandWriteApplication writes;
    public AdminBrandController(BrandWriteApplication writes) { this.writes = writes; }
    @PostMapping
    ResponseEntity<String> create(@RequestBody JsonNode body, @RequestHeader(value="Idempotency-Key", required=false) String key,
        @AuthenticationPrincipal AccountView actor, HttpServletRequest request) {
        var input = input(body, false);
        BrandWriteService.validate(null, input.name(), input.enabled(), null);
        return response(writes.save(actor, null, input.name(), input.enabled(), null, key, TraceFilter.id(request)));
    }
    @PutMapping("/{id}")
    ResponseEntity<String> update(@PathVariable String id, @RequestBody JsonNode body, @RequestHeader(value="Idempotency-Key", required=false) String key,
        @AuthenticationPrincipal AccountView actor, HttpServletRequest request) {
        var input = input(body, true);
        BrandWriteService.validate(id, input.name(), input.enabled(), input.version());
        return response(writes.save(actor, id, input.name(), input.enabled(), input.version(), key, TraceFilter.id(request)));
    }
    private ResponseEntity<String> response(CatalogWriteExecutor.Response result) {
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
    // Validate wire types before converting: Jackson's default DTO coercion accepts strings as
    // booleans and truncates fractional versions, neither of which meets the API contract.
    private Input input(JsonNode body, boolean update) {
        if (body == null || !body.isObject() || body.size() != (update ? 3 : 2)
            || !body.path("name").isTextual() || !body.path("enabled").isBoolean()
            || (update && (!body.path("version").isIntegralNumber() || !body.path("version").canConvertToLong())))
            throw new ApiException(400, "VALIDATION_ERROR", "品牌请求字段或类型不合法");
        return new Input(body.path("name").textValue(), body.path("enabled").booleanValue(), update ? body.path("version").longValue() : null);
    }
    private record Input(String name, boolean enabled, Long version) {}
}
