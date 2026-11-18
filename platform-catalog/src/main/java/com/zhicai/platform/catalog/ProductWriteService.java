package com.zhicai.platform.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.zhicai.platform.catalog.CatalogModels.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ProductWriteService {
    public record Input(String name, String categoryId, String brandId, List<String> imageAssetIds, String description, Boolean featured) {}
    public record SkuInput(String code, List<Spec> specs, String priceMinor, Boolean enabled) {}
    public record ProductChange(Product before, Product after) {}
    public record SkuChange(Sku before, Sku after) {}
    private final ProductWriteMapper mapper;
    private final CatalogQueryService queries;
    private final ObjectMapper json;
    public ProductWriteService(ProductWriteMapper mapper, CatalogQueryService queries, ObjectMapper json) { this.mapper = mapper; this.queries = queries; this.json = json; }

    public static void validate(Input input) {
        if (input == null || !text(input.name(), 120) || input.description() == null || input.description().codePointCount(0, input.description().length()) > 10000 || input.featured() == null) bad();
        id(input.categoryId()); id(input.brandId());
        if (input.imageAssetIds() == null || input.imageAssetIds().isEmpty() || input.imageAssetIds().size() > 10) bad();
        input.imageAssetIds().forEach(ProductWriteService::id);
        if (new HashSet<>(input.imageAssetIds()).size() != input.imageAssetIds().size()) bad();
    }
    public static void validate(SkuInput input) {
        if (input == null || input.code() == null || !input.code().matches("[A-Za-z0-9_-]{1,64}") || input.enabled() == null
            || input.priceMinor() == null || !input.priceMinor().matches("[1-9][0-9]{0,17}")
            || input.specs() == null || input.specs().isEmpty() || input.specs().size() > 10) bad();
        Set<String> names = new HashSet<>();
        for (Spec spec : input.specs()) if (spec == null || !text(spec.name(), 120) || !text(spec.value(), 100) || !names.add(spec.name())) bad();
    }
    public static void identityVersion(String id, Long version) {
        id(id); if (version == null || version < 0 || version >= 9007199254740991L) bad();
    }
    public static void stateRequest(String id, String state, Long version) {
        identityVersion(id, version); if (!Set.of("ON_SALE", "OFF_SALE").contains(state == null ? "" : state)) bad();
    }
    public ProductChange create(Input input) {
        validate(input); references(input, false);
        var draft = new ProductWriteMapper.NewProduct(input);
        if (mapper.insertProduct(draft) != 1 || draft.id == null) throw new IllegalStateException("Product identity not returned");
        String id = draft.id.toString(); images(id, input.imageAssetIds());
        return new ProductChange(null, queries.adminProduct(id));
    }
    public ProductChange update(String id, Input input, Long version) {
        validate(input); identityVersion(id, version); Product before = locked(id);
        version(before.version(), version); references(input, before.state().equals("ON_SALE"));
        if (before.state().equals("ON_SALE")) sellable(before.skus());
        if (mapper.updateProduct(id, input, version) != 1) conflict();
        mapper.deleteImages(id); images(id, input.imageAssetIds());
        return new ProductChange(before, queries.adminProduct(id));
    }
    public ProductChange state(String id, String state, Long version) {
        stateRequest(id, state, version); Product before = locked(id); version(before.version(), version);
        if (state.equals("ON_SALE")) {
            references(new Input(before.name(), before.categoryId(), before.brandId(), before.imageAssetIds(), before.description(), before.featured()), true);
            sellable(before.skus());
        }
        if (mapper.updateState(id, state, version) != 1) conflict();
        return new ProductChange(before, queries.adminProduct(id));
    }
    public SkuChange createSku(String productId, SkuInput input) {
        id(productId); validate(input); locked(productId);
        String specs = combination(input.specs()); uniqueSpecs(productId, null, specs);
        var draft = new ProductWriteMapper.NewSku(productId, input, specs, hash(specs));
        try { if (mapper.insertSku(draft) != 1 || draft.id == null) throw new IllegalStateException("SKU identity not returned"); }
        catch (DuplicateKeyException error) { throw skuConflict(); }
        return new SkuChange(null, new Sku(draft.id.toString(), productId, input.code(), input.specs(), input.priceMinor(), input.enabled(), 0));
    }
    public SkuChange updateSku(String id, SkuInput input, Long version) {
        identityVersion(id, version); validate(input);
        var initial = mapper.findSku(id); if (initial == null) missing();
        Product product = locked(initial.productId()); // Read again after acquiring the root lock.
        var row = mapper.findSku(id); if (row == null) missing(); version(row.version(), version);
        String specs = combination(input.specs()); uniqueSpecs(row.productId(), id, specs);
        if (product.state().equals("ON_SALE") && !input.enabled()
            && product.skus().stream().noneMatch(s -> !s.id().equals(id) && s.enabled() && positive(s.priceMinor()))) notPublishable();
        try { if (mapper.updateSku(id, input, specs, hash(specs), version) != 1) conflict(); }
        catch (DuplicateKeyException error) { throw skuConflict(); }
        return new SkuChange(new Sku(row.id(), row.productId(), row.code(), parse(row.specsJson()), row.priceMinor(), row.enabled(), row.version()),
            new Sku(id, row.productId(), input.code(), input.specs(), input.priceMinor(), input.enabled(), version + 1));
    }
    private Product locked(String id) { if (mapper.lockProduct(id) == null) missing(); return queries.adminProduct(id); }
    private void references(Input input, boolean publishing) {
        Boolean category = mapper.categoryEnabled(input.categoryId()), brand = mapper.brandEnabled(input.brandId());
        if (category == null || brand == null) missing();
        if (publishing && (!category || !brand || !text(input.name(), 120))) notPublishable();
        if (input.imageAssetIds().isEmpty() || !new HashSet<>(mapper.publicAssets(input.imageAssetIds())).equals(new HashSet<>(input.imageAssetIds())))
            throw new ApiException(400, "INVALID_IMAGE_ASSET", "商品图片必须为现有公开 JPEG、PNG 或 WebP 资源，且单张不超过5MiB");
    }
    private void images(String id, List<String> ids) { for (int i = 0; i < ids.size(); i++) if (mapper.insertImage(id, ids.get(i), i) != 1) throw new IllegalStateException("Image association not inserted"); }
    private void uniqueSpecs(String productId, String except, String canonical) {
        for (var sku : mapper.skus(productId)) if (!sku.id().equals(except) && combination(parse(sku.specsJson())).equals(canonical))
            throw new ApiException(409, "SKU_SPECS_CONFLICT", "同一商品不能重复规格组合");
    }
    private List<Spec> parse(String source) {
        try { return json.readValue(source, new TypeReference<List<Spec>>() {}); }
        catch (Exception error) { throw new IllegalStateException("Invalid stored SKU specs", error); }
    }
    private String combination(List<Spec> specs) {
        try { return json.writeValueAsString(specs.stream().sorted(Comparator.comparing(Spec::name)).toList()); }
        catch (Exception error) { throw new IllegalStateException("SKU serialization failed", error); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static boolean text(String text, int max) { return text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= max; }
    private static boolean positive(String price) { return price != null && price.matches("[1-9][0-9]{0,17}"); }
    private static void sellable(List<Sku> skus) { if (skus.stream().noneMatch(s -> s.enabled() && positive(s.priceMinor()))) notPublishable(); }
    private static void version(long current, long expected) { if (current != expected) conflict(); }
    private static void id(String id) { if (id == null || !id.matches("[1-9][0-9]{0,18}")) bad(); }
    private static void bad() { throw new ApiException(400, "VALIDATION_ERROR", "商品或SKU请求字段不合法"); }
    private static void missing() { throw new ApiException(404, "NOT_FOUND", "商品、SKU、分类或品牌不存在"); }
    private static void conflict() { throw new ApiException(409, "VERSION_CONFLICT", "数据已更新，请重新加载后编辑"); }
    private static void notPublishable() { throw new ApiException(409, "PRODUCT_NOT_PUBLISHABLE", "上架需启用的分类、品牌、公开主图及至少一个启用的正价SKU；请先下架再停用最后一个SKU"); }
    private static ApiException skuConflict() { return new ApiException(409, "SKU_CONFLICT", "SKU编码或规格组合已存在"); }
}
