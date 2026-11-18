package com.zhicai.platform.inventory;

import com.zhicai.platform.common.ApiException;
import java.util.*;

/** Public read-only inventory boundary. Availability is evidence, never an inventory reservation. */
public class InventoryAvailabilityService {
    private final InventoryAvailabilityMapper mapper;
    public InventoryAvailabilityService(InventoryAvailabilityMapper mapper){this.mapper=Objects.requireNonNull(mapper);}

    public String selectWarehouse(List<StockPolicy.Demand> demands){
        if(demands==null || demands.isEmpty() || demands.size()>100)bad();
        var merged=new LinkedHashMap<String,Integer>();
        for(var demand:demands){
            if(demand==null || demand.skuId()==null || !demand.skuId().matches("[1-9][0-9]{0,18}") || demand.quantity()<1 || demand.quantity()>99)bad();
            int quantity=merged.getOrDefault(demand.skuId(),0)+demand.quantity();if(quantity>99)bad();merged.put(demand.skuId(),quantity);
        }
        var required=merged.entrySet().stream().map(row->new StockPolicy.Demand(row.getKey(),row.getValue())).toList();
        var warehouses=mapper.warehouses();
        var candidates=warehouses.stream().filter(row->row.enabled() && row.fulfillmentEnabled()).map(StockPolicy.Warehouse::id).toList();
        var balances=candidates.isEmpty()?List.<StockPolicy.Balance>of():mapper.balances(candidates,List.copyOf(merged.keySet()));
        return StockPolicy.selectWarehouse(warehouses,required,balances);
    }
    private static void bad(){throw new ApiException(400,"VALIDATION_ERROR","库存需求SKU或数量不合法");}
}
