package com.zhicai.platform.catalog;

import com.zhicai.platform.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrandWriteService {
    public record Change(CatalogModels.Brand before, CatalogModels.Brand after) {}
    private final BrandWriteMapper mapper;
    public BrandWriteService(BrandWriteMapper mapper) { this.mapper = mapper; }
    public static void validate(String id, String name, Boolean enabled, Long version) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 120 || enabled == null)
            throw new ApiException(400, "VALIDATION_ERROR", "品牌名称需为1–120个字符，且必须指定启用状态");
        if (id != null && (!id.matches("[1-9][0-9]{0,18}") || version == null || version < 0 || version >= 9007199254740991L))
            throw new ApiException(400, "VALIDATION_ERROR", "品牌ID或版本不合法");
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public Change create(String name, Boolean enabled) {
        validate(null, name, enabled, null);
        var draft = new BrandWriteMapper.NewBrand(name, enabled);
        if (mapper.insert(draft) != 1 || draft.id == null) throw new IllegalStateException("Brand insert did not return identity");
        return new Change(null, new CatalogModels.Brand(draft.id.toString(), name, enabled, 0));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public Change update(String id, String name, Boolean enabled, Long version) {
        if (id == null) throw new ApiException(400, "VALIDATION_ERROR", "品牌ID不合法");
        validate(id, name, enabled, version);
        var before = mapper.find(id);
        if (before == null) throw new ApiException(404, "NOT_FOUND", "品牌不存在");
        if (before.version() != version || mapper.update(id, name, enabled, version) != 1)
            throw new ApiException(409, "VERSION_CONFLICT", "品牌已被其他操作更新，请重新加载后编辑");
        return new Change(before, new CatalogModels.Brand(id, name, enabled, version + 1));
    }
}
