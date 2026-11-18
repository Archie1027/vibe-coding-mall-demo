package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.ProductWriteService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class ProductWriteApplication {
    private final ProductWriteService products;
    private final ObjectProvider<CatalogWriteExecutor> executors;
    private final ObjectMapper json;
    public ProductWriteApplication(ProductWriteService products, @org.springframework.beans.factory.annotation.Qualifier("catalogWriteExecutor") ObjectProvider<CatalogWriteExecutor> executors, ObjectMapper json) {
        this.products = products; this.executors = executors; this.json = json;
    }
    public CatalogWriteExecutor.Response saveProduct(AccountView actor, String id, ProductWriteService.Input input, Long version, String key, String trace) {
        authorize(actor); ProductWriteService.validate(input); if (id != null) ProductWriteService.identityVersion(id, version);
        var executor = executor(key);
        var body = json.valueToTree(input); if (id != null) ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("version", version);
        return executor.execute(new CatalogWriteExecutor.Request(actor.id(), id == null ? "POST" : "PUT",
            id == null ? "/api/v1/admin/products" : "/api/v1/admin/products/" + id, key, body, trace), () -> {
                var change = id == null ? products.create(input) : products.update(id, input, version);
                return new CatalogWriteExecutor.Outcome(id == null ? 201 : 200, change.after(), audit(id == null ? "PRODUCT_CREATE" : "PRODUCT_UPDATE", "PRODUCT", change.after().id(), change.before(), change.after()));
            });
    }
    public CatalogWriteExecutor.Response state(AccountView actor, String id, String state, Long version, String key, String trace) {
        authorize(actor); ProductWriteService.stateRequest(id, state, version); var executor = executor(key);
        return executor.execute(new CatalogWriteExecutor.Request(actor.id(), "POST", "/api/v1/admin/products/" + id + "/state", key,
            json.createObjectNode().put("state", state).put("version", version), trace), () -> {
                var change = products.state(id, state, version);
                return new CatalogWriteExecutor.Outcome(200, change.after(), audit("PRODUCT_STATE", "PRODUCT", id, change.before(), change.after()));
            });
    }
    public CatalogWriteExecutor.Response saveSku(AccountView actor, String productId, String id, ProductWriteService.SkuInput input, Long version, String key, String trace) {
        authorize(actor); ProductWriteService.validate(input);
        if (id == null) ProductWriteService.identityVersion(productId, 0L); else ProductWriteService.identityVersion(id, version);
        var executor = executor(key); var body = json.valueToTree(input);
        if (id != null) ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("version", version);
        return executor.execute(new CatalogWriteExecutor.Request(actor.id(), id == null ? "POST" : "PUT",
            id == null ? "/api/v1/admin/products/" + productId + "/skus" : "/api/v1/admin/skus/" + id, key, body, trace), () -> {
                var change = id == null ? products.createSku(productId, input) : products.updateSku(id, input, version);
                return new CatalogWriteExecutor.Outcome(id == null ? 201 : 200, change.after(), audit(id == null ? "SKU_CREATE" : "SKU_UPDATE", "SKU", change.after().id(), change.before(), change.after()));
            });
    }
    private CatalogWriteExecutor.Audit audit(String action, String type, String id, Object before, Object after) {
        return new CatalogWriteExecutor.Audit(action, type, id, json.valueToTree(before), json.valueToTree(after));
    }
    private CatalogWriteExecutor executor(String key) {
        new WriteRequestFingerprint(json).keyHash(key);
        var result = executors.getIfAvailable();
        if (result == null) throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "商品写入功能尚未启用");
        return result;
    }
    private void authorize(AccountView actor) {
        if (actor == null) throw ApiException.unauthenticated();
        if (actor.accountType() != AccountType.STAFF || !actor.permissions().contains("catalog:write")) throw new ApiException(403, "FORBIDDEN", "没有商品维护权限");
    }
}
