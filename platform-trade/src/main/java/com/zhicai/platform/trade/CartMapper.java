package com.zhicai.platform.trade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface CartMapper {
    record Row(String id, String skuId, int quantity, boolean selected) {}
    List<Row> list(@Param("owner") String owner, @Param("offset") long offset, @Param("size") int size);
    long count(@Param("owner") String owner);
    Row find(@Param("owner") String owner, @Param("id") String id);
    Row findBySku(@Param("owner") String owner, @Param("skuId") String skuId);
    int insert(NewItem item);
    int update(@Param("owner") String owner, @Param("id") String id, @Param("quantity") int quantity,
               @Param("selected") boolean selected);
    int delete(@Param("owner") String owner, @Param("id") String id);
    final class NewItem {
        public Long id;
        public final String owner, skuId;
        public final int quantity;
        public NewItem(String owner, String skuId, int quantity) { this.owner = owner; this.skuId = skuId; this.quantity = quantity; }
    }
}
