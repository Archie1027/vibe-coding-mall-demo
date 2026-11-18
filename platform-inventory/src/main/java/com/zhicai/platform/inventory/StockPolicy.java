package com.zhicai.platform.inventory;

import com.zhicai.platform.common.ApiException;
import java.math.BigInteger;
import java.util.*;

/** Read-only selection rules; real reservation still requires ordered database locks and conditional updates. */
public final class StockPolicy {
    private StockPolicy() {}
    public record Warehouse(String id,int priority,boolean enabled,boolean fulfillmentEnabled) {}
    public record Demand(String skuId,int quantity) {}
    public record Balance(String warehouseId,String skuId,int onHand,int reserved,int quarantine) {
        public int available(){
            if(onHand<reserved || reserved<0 || quarantine<0)throw new IllegalArgumentException("Corrupt stock balance");
            return onHand-reserved;
        }
    }
    public static String selectWarehouse(List<Warehouse> warehouses,List<Demand> demands,List<Balance> balances){
        if(demands==null || demands.isEmpty() || demands.size()>100)badRequest("库存需求必须为1至100行");
        var required=new LinkedHashMap<String,Integer>();
        for(var row:demands){
            if(row==null || !validId(row.skuId()) || row.quantity()<1 || row.quantity()>99)badRequest("SKU或数量不合法");
            int quantity=required.getOrDefault(row.skuId(),0)+row.quantity();
            if(quantity>99)badRequest("同一SKU合计数量不能超过99");required.put(row.skuId(),quantity);
        }
        if(warehouses==null || balances==null)throw new IllegalArgumentException("Warehouse and balance evidence required");
        var candidates=new ArrayList<Warehouse>();var ids=new HashSet<String>();
        for(var warehouse:warehouses){
            if(warehouse==null || !validId(warehouse.id()) || !ids.add(warehouse.id()))throw new IllegalArgumentException("Invalid or duplicate warehouse");
            if(warehouse.enabled() && warehouse.fulfillmentEnabled())candidates.add(warehouse);
        }
        var stock=new HashMap<String,Map<String,Integer>>();
        for(var balance:balances){
            if(balance==null || !validId(balance.warehouseId()) || !validId(balance.skuId()))throw new IllegalArgumentException("Invalid stock identity");
            var warehouse=stock.computeIfAbsent(balance.warehouseId(),unused->new HashMap<>());
            if(warehouse.putIfAbsent(balance.skuId(),balance.available())!=null)throw new IllegalArgumentException("Duplicate stock balance");
        }
        candidates.sort(Comparator.comparingInt(Warehouse::priority).thenComparing(warehouse->new BigInteger(warehouse.id())));
        for(var candidate:candidates){
            var available=stock.getOrDefault(candidate.id(),Map.of());
            if(required.entrySet().stream().allMatch(row->available.getOrDefault(row.getKey(),0)>=row.getValue()))return candidate.id();
        }
        throw new ApiException(409,"NO_SINGLE_WAREHOUSE","没有仓库能一次满足全部商品，请调整商品或稍后重试");
    }
    private static boolean validId(String id){return id!=null && id.matches("[1-9][0-9]{0,18}");}
    private static void badRequest(String message){throw new ApiException(400,"VALIDATION_ERROR",message);}
}
