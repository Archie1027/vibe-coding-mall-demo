package com.zhicai.platform.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface CategoryWriteMapper {
    String lockAnchor();
    List<CatalogModels.Category> tree();
    int insert(NewCategory category);
    int update(@Param("id") String id, @Param("name") String name, @Param("parentId") String parentId,
               @Param("sort") int sort, @Param("enabled") boolean enabled, @Param("version") long version);
    final class NewCategory {
        public Long id;
        public final String name, parentId;
        public final int sort;
        public final boolean enabled;
        public NewCategory(String name, String parentId, int sort, boolean enabled) {
            this.name = name; this.parentId = parentId; this.sort = sort; this.enabled = enabled;
        }
    }
}
