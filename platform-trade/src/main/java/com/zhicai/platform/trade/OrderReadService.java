package com.zhicai.platform.trade;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.CatalogModels.PageData;
import com.zhicai.platform.catalog.CatalogModels.Spec;
import com.zhicai.platform.trade.OrderReadMapper.LineRow;
import com.zhicai.platform.trade.OrderReadMapper.OrderRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderReadService {
    private final OrderReadMapper mapper;
    private final ObjectMapper json;
    public OrderReadService(OrderReadMapper mapper,ObjectMapper json){this.mapper=mapper;this.json=json;}

    @Transactional(readOnly=true)
    public PageData<OrderModels.Order> list(String owner,int page,int size,String state){
        TradeValidation.id(owner);
        long offset=TradeValidation.offset(page,size);
        state=state(state);
        var rows=mapper.list(owner,state,offset,size);
        var items=hydrate(rows);
        return new PageData<>(items,page,size,mapper.count(owner,state));
    }

    @Transactional(readOnly=true)
    public OrderModels.Order find(String owner,String id){
        TradeValidation.id(owner);TradeValidation.id(id);
        var row=mapper.find(owner,id);
        if(row==null)throw TradeValidation.notFound("订单");
        return hydrate(List.of(row)).getFirst();
    }

    private List<OrderModels.Order> hydrate(List<OrderRow> rows){
        if(rows.isEmpty())return List.of();
        var ids=rows.stream().map(OrderRow::id).toList();
        Map<String,List<OrderModels.Line>> byOrder=new HashMap<>();
        for(LineRow line:mapper.lines(ids)){
            if(!ids.contains(line.orderId()))throw new IllegalStateException("Order line outside selected orders");
            byOrder.computeIfAbsent(line.orderId(),ignored->new ArrayList<>()).add(new OrderModels.Line(
                line.id(),line.skuId(),line.productName(),decode(line.specsSnapshotJson(),new TypeReference<List<Spec>>(){}),
                line.quantity(),line.unitPriceMinor(),line.grossMinor(),line.discountMinor(),line.payableMinor(),
                line.returnedQuantity(),line.refundedMinor()));
        }
        return rows.stream().map(row->new OrderModels.Order(row.id(),row.orderNo(),row.customerId(),row.warehouseId(),
            row.state(),byOrder.getOrDefault(row.id(),List.of()),decode(row.addressSnapshotJson(),TradeModels.AddressInput.class),
            row.grossMinor(),row.discountMinor(),row.shippingMinor(),row.payableMinor(),row.refundedMinor(),
            row.expiresAt(),row.createdAt(),row.version())).toList();
    }
    private <T> T decode(String value,Class<T> type){
        try{return json.readValue(value,type);}catch(Exception error){throw new IllegalStateException("Invalid persisted order snapshot",error);}
    }
    private <T> T decode(String value,TypeReference<T> type){
        try{return json.readValue(value,type);}catch(Exception error){throw new IllegalStateException("Invalid persisted order line snapshot",error);}
    }
    private static String state(String state){
        if(state==null)return null;
        if(!List.of("UNPAID","CLOSING","CANCELLED","PAID","SHIPPED","COMPLETED").contains(state))
            TradeValidation.bad("订单状态不合法");
        return state;
    }
}
