package com.zhicai.platform.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AssetWriteMapper {
    int insert(NewAsset asset);
    PublicAsset publicByKey(@Param("key") String key);
    record PublicAsset(String id,String storageKey,String contentType,long sizeBytes) {}
    final class NewAsset {
        public Long id;
        public final String storageKey,contentType,ownerId;
        public final long sizeBytes;
        public NewAsset(String key,String type,long size,String owner){storageKey=key;contentType=type;sizeBytes=size;ownerId=owner;}
    }
}
