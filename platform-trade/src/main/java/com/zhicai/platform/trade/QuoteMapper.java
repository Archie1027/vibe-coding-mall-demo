package com.zhicai.platform.trade;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QuoteMapper {
    int insert(NewQuote quote);
    final class NewQuote {
        public String id;
        public final String owner;
        public final String warehouseId;
        public final String snapshotJson;
        public final Instant expiresAt;
        public final Instant createdAt;
        public NewQuote(String owner,String warehouseId,String snapshotJson,Instant expiresAt,Instant createdAt){
            this.owner=owner;this.warehouseId=warehouseId;this.snapshotJson=snapshotJson;this.expiresAt=expiresAt;this.createdAt=createdAt;
        }
    }
}
