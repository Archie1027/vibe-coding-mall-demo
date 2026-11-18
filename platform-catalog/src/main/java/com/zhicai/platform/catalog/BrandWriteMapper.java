package com.zhicai.platform.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BrandWriteMapper {
    CatalogModels.Brand find(@Param("id") String id);
    int insert(NewBrand brand);
    int update(@Param("id") String id, @Param("name") String name, @Param("enabled") boolean enabled, @Param("version") long version);
    final class NewBrand {
        public Long id;
        public final String name;
        public final boolean enabled;
        public NewBrand(String name, boolean enabled) { this.name = name; this.enabled = enabled; }
    }
}
