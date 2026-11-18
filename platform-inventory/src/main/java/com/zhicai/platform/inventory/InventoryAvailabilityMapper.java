package com.zhicai.platform.inventory;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Non-locking SELECTs only. Trade calls the public availability service, never this mapper. */
@Mapper
public interface InventoryAvailabilityMapper {
    List<StockPolicy.Warehouse> warehouses();
    List<StockPolicy.Balance> balances(@Param("warehouseIds") List<String> warehouseIds,@Param("skuIds") List<String> skuIds);
}
