package com.zhicai.platform.trade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AddressMapper {
    List<TradeModels.Address> list(@Param("owner") String owner, @Param("offset") long offset, @Param("size") int size);
    long count(@Param("owner") String owner);
    TradeModels.Address find(@Param("owner") String owner, @Param("id") String id);
    TradeModels.Address findDefault(@Param("owner") String owner);
    int clearDefault(@Param("owner") String owner, @Param("exceptId") String exceptId);
    int insert(NewAddress address);
    int update(@Param("owner") String owner, @Param("id") String id, @Param("input") TradeModels.AddressInput input,
               @Param("version") long version);
    int delete(@Param("owner") String owner, @Param("id") String id);
    final class NewAddress {
        public Long id;
        public final String owner;
        public final TradeModels.AddressInput input;
        public NewAddress(String owner, TradeModels.AddressInput input) { this.owner = owner; this.input = input; }
    }
}
