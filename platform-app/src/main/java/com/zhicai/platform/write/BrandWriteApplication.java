package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.BrandWriteService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.identity.AccountType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class BrandWriteApplication {
    private final BrandWriteService brands;
    private final ObjectProvider<CatalogWriteExecutor> executors;
    private final ObjectMapper json;
    public BrandWriteApplication(BrandWriteService brands, @org.springframework.beans.factory.annotation.Qualifier("catalogWriteExecutor") ObjectProvider<CatalogWriteExecutor> executors, ObjectMapper json) {
        this.brands = brands; this.executors = executors; this.json = json;
    }
    public CatalogWriteExecutor.Response save(AccountView actor, String id, String name, Boolean enabled, Long version, String key, String trace) {
        if (actor == null) throw ApiException.unauthenticated();
        if (actor.accountType() != AccountType.STAFF || !actor.permissions().contains("catalog:write"))
            throw new ApiException(403, "FORBIDDEN", "没有品牌维护权限");
        BrandWriteService.validate(id, name, enabled, version);
        new WriteRequestFingerprint(json).keyHash(key);
        var executor = executors.getIfAvailable();
        if (executor == null) throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "品牌写入功能尚未启用");
        var body = json.createObjectNode().put("name", name).put("enabled", enabled);
        if (id != null) body.put("version", version);
        return executor.execute(new CatalogWriteExecutor.Request(actor.id(), id == null ? "POST" : "PUT",
            id == null ? "/api/v1/admin/brands" : "/api/v1/admin/brands/" + id, key, body, trace), () -> {
                var change = id == null ? brands.create(name, enabled) : brands.update(id, name, enabled, version);
                return new CatalogWriteExecutor.Outcome(id == null ? 201 : 200, change.after(), new CatalogWriteExecutor.Audit(
                    id == null ? "BRAND_CREATE" : "BRAND_UPDATE", "BRAND", change.after().id(), json.valueToTree(change.before()), json.valueToTree(change.after())));
            });
    }
}
