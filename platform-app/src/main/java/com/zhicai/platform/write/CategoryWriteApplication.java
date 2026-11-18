package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.CategoryWriteService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class CategoryWriteApplication {
    private final CategoryWriteService categories;
    private final ObjectProvider<CatalogWriteExecutor> executors;
    private final ObjectMapper json;
    public CategoryWriteApplication(CategoryWriteService categories, @org.springframework.beans.factory.annotation.Qualifier("catalogWriteExecutor") ObjectProvider<CatalogWriteExecutor> executors, ObjectMapper json) {
        this.categories = categories; this.executors = executors; this.json = json;
    }
    public CatalogWriteExecutor.Response save(AccountView actor, String id, String name, String parentId, Integer sort, Boolean enabled, Long version, String key, String trace) {
        if (actor == null) throw ApiException.unauthenticated();
        if (actor.accountType() != AccountType.STAFF || !actor.permissions().contains("catalog:write")) throw new ApiException(403, "FORBIDDEN", "没有分类维护权限");
        CategoryWriteService.validate(id, name, parentId, sort, enabled, version);
        new WriteRequestFingerprint(json).keyHash(key);
        var executor = executors.getIfAvailable();
        if (executor == null) throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "分类写入功能尚未启用");
        var body = json.createObjectNode().put("name", name).put("parentId", parentId).put("sort", sort).put("enabled", enabled);
        if (id != null) body.put("version", version);
        return executor.execute(new CatalogWriteExecutor.Request(actor.id(), id == null ? "POST" : "PUT",
            id == null ? "/api/v1/admin/categories" : "/api/v1/admin/categories/" + id, key, body, trace), () -> {
                var change = id == null ? categories.create(name, parentId, sort, enabled) : categories.update(id, name, parentId, sort, enabled, version);
                return new CatalogWriteExecutor.Outcome(id == null ? 201 : 200, change.after(), new CatalogWriteExecutor.Audit(
                    id == null ? "CATEGORY_CREATE" : "CATEGORY_UPDATE", "CATEGORY", change.after().id(), json.valueToTree(change.before()), json.valueToTree(change.after())));
            });
    }
}
