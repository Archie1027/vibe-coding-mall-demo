package com.zhicai.platform.catalog;

import com.zhicai.platform.catalog.CatalogModels.Category;
import com.zhicai.platform.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CategoryWriteService {
    public record Change(CatalogModels.Category before, CatalogModels.Category after) {}
    private final CategoryWriteMapper mapper;
    public CategoryWriteService(CategoryWriteMapper mapper) { this.mapper = mapper; }
    public static void validate(String id, String name, String parentId, Integer sort, Boolean enabled, Long version) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 120 || enabled == null || sort == null || sort < 0
            || (parentId != null && !parentId.matches("[1-9][0-9]{0,18}"))
            || (id != null && (!id.matches("[1-9][0-9]{0,18}") || version == null || version < 0 || version >= 9007199254740991L)))
            throw new ApiException(400, "VALIDATION_ERROR", "分类名称、父级、排序、启用状态或版本不合法");
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public Change create(String name, String parentId, Integer sort, Boolean enabled) {
        validate(null, name, parentId, sort, enabled, null);
        var tree = lockedTree();
        requireParent(tree, parentId);
        tree.put("__new_category__", new Category("__new_category__", name, parentId, sort, enabled, 0));
        validateTree(tree);
        var draft = new CategoryWriteMapper.NewCategory(name, parentId, sort, enabled);
        if (mapper.insert(draft) != 1 || draft.id == null) throw new IllegalStateException("Category insert did not return identity");
        return new Change(null, new Category(draft.id.toString(), name, parentId, sort, enabled, 0));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public Change update(String id, String name, String parentId, Integer sort, Boolean enabled, Long version) {
        if (id == null) throw new ApiException(400, "VALIDATION_ERROR", "分类ID不合法");
        validate(id, name, parentId, sort, enabled, version);
        var tree = lockedTree();
        var before = tree.get(id);
        if (before == null) throw new ApiException(404, "NOT_FOUND", "分类不存在");
        if (before.version() != version) throw versionConflict();
        requireParent(tree, parentId);
        var after = new Category(id, name, parentId, sort, enabled, version + 1);
        tree.put(id, after); validateTree(tree);
        if (mapper.update(id, name, parentId, sort, enabled, version) != 1) throw versionConflict();
        return new Change(before, after);
    }
    private Map<String, Category> lockedTree() {
        // Every category write locks the same stable minimum ID. No category DELETE API exists.
        // The executor owns a READ_COMMITTED transaction; read the tree only after this lock.
        String anchor = mapper.lockAnchor();
        Map<String, Category> tree = new LinkedHashMap<>();
        if (anchor != null) for (var node : mapper.tree()) tree.put(node.id(), node);
        return tree;
    }
    private void requireParent(Map<String, Category> tree, String parentId) {
        if (parentId != null && !tree.containsKey(parentId)) throw new ApiException(404, "NOT_FOUND", "父分类不存在");
    }
    private void validateTree(Map<String, Category> tree) {
        for (var node : tree.values()) {
            Set<String> seen = new HashSet<>(); int depth = 0;
            for (Category cursor = node; cursor != null; cursor = cursor.parentId() == null ? null : tree.get(cursor.parentId())) {
                if (!seen.add(cursor.id())) throw new ApiException(409, "CATEGORY_CYCLE", "分类不能指向自身或子孙分类");
                if (++depth > 3) throw new ApiException(409, "CATEGORY_DEPTH_EXCEEDED", "移动后整棵分类树不得超过三层");
                if (cursor.parentId() != null && !tree.containsKey(cursor.parentId())) throw new ApiException(409, "CATEGORY_TREE_INVALID", "现有分类树不完整，请先检查数据");
            }
        }
    }
    private ApiException versionConflict() { return new ApiException(409, "VERSION_CONFLICT", "分类已被其他操作更新，请重新加载后编辑"); }
}
