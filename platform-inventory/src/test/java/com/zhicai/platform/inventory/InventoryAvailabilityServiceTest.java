package com.zhicai.platform.inventory;

import com.zhicai.platform.common.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InventoryAvailabilityServiceTest {
    final InventoryAvailabilityMapper mapper=mock(InventoryAvailabilityMapper.class);
    final InventoryAvailabilityService service=new InventoryAvailabilityService(mapper);
    @Test void batchReadsWithoutReservationsAndSelectsByExistingStockPolicy(){
        when(mapper.warehouses()).thenReturn(List.of(new StockPolicy.Warehouse("10",1,true,true),new StockPolicy.Warehouse("2",1,true,true)));
        when(mapper.balances(anyList(),anyList())).thenReturn(List.of(new StockPolicy.Balance("2","3",6,2,99),new StockPolicy.Balance("10","3",10,0,0)));
        assertThat(service.selectWarehouse(List.of(new StockPolicy.Demand("3",2),new StockPolicy.Demand("3",2)))).isEqualTo("2");
        verify(mapper).warehouses();verify(mapper).balances(List.of("10","2"),List.of("3"));verifyNoMoreInteractions(mapper);
    }
    @Test void invalidDemandFailsBeforeAnySql(){
        for(List<StockPolicy.Demand> rows:Arrays.<List<StockPolicy.Demand>>asList(null,List.of(),Collections.singletonList(null),List.of(new StockPolicy.Demand("0",1)),List.of(new StockPolicy.Demand("3",100)),List.of(new StockPolicy.Demand("3",60),new StockPolicy.Demand("3",40)),Collections.nCopies(101,new StockPolicy.Demand("3",1))))assertThatThrownBy(()->service.selectWarehouse(rows)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
    @Test void crossWarehouseSumDoesNotPretendOneWarehouseCanFulfilEverything(){
        when(mapper.warehouses()).thenReturn(List.of(new StockPolicy.Warehouse("1",1,true,true),new StockPolicy.Warehouse("2",2,true,true)));
        when(mapper.balances(anyList(),anyList())).thenReturn(List.of(new StockPolicy.Balance("1","3",2,0,0),new StockPolicy.Balance("2","3",2,0,0)));
        assertThatThrownBy(()->service.selectWarehouse(List.of(new StockPolicy.Demand("3",3)))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("NO_SINGLE_WAREHOUSE"));
    }
    @Test void noWarehouseDoesNotIssueEmptyInSql(){when(mapper.warehouses()).thenReturn(List.of());assertThatThrownBy(()->service.selectWarehouse(List.of(new StockPolicy.Demand("3",1)))).isInstanceOf(ApiException.class);verify(mapper).warehouses();verifyNoMoreInteractions(mapper);}
}
