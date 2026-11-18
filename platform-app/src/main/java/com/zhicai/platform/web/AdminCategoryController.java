package com.zhicai.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhicai.platform.catalog.CategoryWriteService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.write.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/categories")
public class AdminCategoryController {
    private final CategoryWriteApplication writes;
    public AdminCategoryController(CategoryWriteApplication writes) { this.writes = writes; }
    @PostMapping
    ResponseEntity<String> create(@RequestBody JsonNode body, @RequestHeader(value="Idempotency-Key", required=false) String key,
        @AuthenticationPrincipal AccountView actor, HttpServletRequest request) { return save(null, body, key, actor, request); }
    @PutMapping("/{id}")
    ResponseEntity<String> update(@PathVariable String id, @RequestBody JsonNode body, @RequestHeader(value="Idempotency-Key", required=false) String key,
        @AuthenticationPrincipal AccountView actor, HttpServletRequest request) { return save(id, body, key, actor, request); }
    private ResponseEntity<String> save(String id, JsonNode body, String key, AccountView actor, HttpServletRequest request) {
        boolean update = id != null;
        Set<String> fields = update ? Set.of("name", "parentId", "sort", "enabled", "version") : Set.of("name", "parentId", "sort", "enabled");
        if (body == null || !body.isObject() || !body.path("name").isTextual() || !body.path("enabled").isBoolean()
            || !body.path("sort").isIntegralNumber() || !body.path("sort").canConvertToInt()
            || (body.hasNonNull("parentId") && !body.path("parentId").isTextual())
            || (update && (!body.path("version").isIntegralNumber() || !body.path("version").canConvertToLong()))) bad();
        body.fieldNames().forEachRemaining(field -> { if (!fields.contains(field)) bad(); });
        String parent = body.hasNonNull("parentId") ? body.path("parentId").textValue() : null;
        Long version = update ? body.path("version").longValue() : null;
        CategoryWriteService.validate(id, body.path("name").textValue(), parent, body.path("sort").intValue(), body.path("enabled").booleanValue(), version);
        var result = writes.save(actor, id, body.path("name").textValue(), parent, body.path("sort").intValue(), body.path("enabled").booleanValue(), version, key, TraceFilter.id(request));
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
    private void bad() { throw new ApiException(400, "VALIDATION_ERROR", "分类请求字段或类型不合法"); }
}
