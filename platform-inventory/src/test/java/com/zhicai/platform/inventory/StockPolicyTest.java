package com.zhicai.platform.inventory;

import com.zhicai.platform.common.ApiException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.zhicai.platform.inventory.StockPolicy.*;

class StockPolicyTest {
    private Warehouse warehouse(String id,int priority){return new Warehouse(id,priority,true,true);}
    private Balance balance(String warehouse,String sku,int onHand,int reserved,int quarantine){return new Balance(warehouse,sku,onHand,reserved,quarantine);}
    private void unavailable(Runnable call){assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(409);assertThat(e.code()).isEqualTo("NO_SINGLE_WAREHOUSE");});}
    @Test void choosesOneWarehouseThatCanSupplyEverySku(){
        var rows=List.of(balance("1","11",9,0,0),balance("1","12",0,0,0),balance("2","11",2,0,0),balance("2","12",3,0,0));
        assertThat(selectWarehouse(List.of(warehouse("1",1),warehouse("2",2)),List.of(new Demand("11",2),new Demand("12",3)),rows)).isEqualTo("2");
    }
    @Test void cannotCombineDifferentWarehousesIntoOneOrder(){
        unavailable(()->selectWarehouse(List.of(warehouse("1",1),warehouse("2",2)),List.of(new Demand("11",1),new Demand("12",1)),List.of(balance("1","11",10,0,0),balance("2","12",10,0,0))));
    }
    @Test void priorityWinsThenNumericWarehouseId(){
        var rows=List.of(balance("10","11",10,0,0),balance("2","11",10,0,0));var demand=List.of(new Demand("11",1));
        assertThat(selectWarehouse(List.of(warehouse("10",2),warehouse("2",1)),demand,rows)).isEqualTo("2");
        assertThat(selectWarehouse(List.of(warehouse("10",1),warehouse("2",1)),demand,rows)).isEqualTo("2");
        assertThat(selectWarehouse(List.of(warehouse("10",0),warehouse("2",1)),demand,rows)).isEqualTo("10");
    }
    @Test void disabledAndNonFulfillmentWarehousesCannotWin(){
        var candidates=List.of(new Warehouse("1",0,false,true),new Warehouse("2",0,true,false),warehouse("3",2));
        assertThat(selectWarehouse(candidates,List.of(new Demand("11",1)),List.of(balance("1","11",10,0,0),balance("2","11",10,0,0),balance("3","11",1,0,0)))).isEqualTo("3");
    }
    @Test void reservedReducesAvailabilityAndQuarantineNeverAddsSupply(){
        var row=balance("1","11",10,9,100);assertThat(row.available()).isEqualTo(1);
        unavailable(()->selectWarehouse(List.of(warehouse("1",1)),List.of(new Demand("11",2)),List.of(row)));
        assertThat(selectWarehouse(List.of(warehouse("1",1)),List.of(new Demand("11",1)),List.of(row))).isEqualTo("1");
    }
    @Test void missingRowsAndEmptyCandidatesReturnSingleWarehouseConflict(){
        unavailable(()->selectWarehouse(List.of(warehouse("1",1)),List.of(new Demand("11",1)),List.of()));
        unavailable(()->selectWarehouse(List.of(),List.of(new Demand("11",1)),List.of()));
    }
    @Test void duplicateDemandCannotUnderstateRequiredStock(){
        var demand=List.of(new Demand("11",2),new Demand("11",2));
        unavailable(()->selectWarehouse(List.of(warehouse("1",1)),demand,List.of(balance("1","11",3,0,0))));
        assertThat(selectWarehouse(List.of(warehouse("1",1)),demand,List.of(balance("1","11",4,0,0)))).isEqualTo("1");
    }
    @Test void invalidQuantitiesIdsAndEmptyRequestsAreRejected(){
        for(var demand:List.of(List.<Demand>of(),List.of(new Demand("0",1)),List.of(new Demand("11",0)),List.of(new Demand("11",100)),List.of(new Demand("11",50),new Demand("11",50))))
            assertThatThrownBy(()->selectWarehouse(List.of(),demand,List.of())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(()->selectWarehouse(List.of(),null,List.of())).isInstanceOf(ApiException.class);
    }
    @Test void corruptBalancesAndDuplicateIdentitiesFailClosed(){
        for(var row:List.of(balance("1","11",1,2,0),balance("1","11",1,-1,0),balance("1","11",1,0,-1)))
            assertThatThrownBy(()->selectWarehouse(List.of(warehouse("1",1)),List.of(new Demand("11",1)),List.of(row))).isInstanceOf(IllegalArgumentException.class);
        var row=balance("1","11",1,0,0);
        assertThatThrownBy(()->selectWarehouse(List.of(warehouse("1",1)),List.of(new Demand("11",1)),List.of(row,row))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->selectWarehouse(List.of(warehouse("1",1),warehouse("1",2)),List.of(new Demand("11",1)),List.of(row))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void selectionDoesNotMutateOrReserveStock(){
        var warehouses=new ArrayList<>(List.of(warehouse("10",1),warehouse("2",1)));var original=List.copyOf(warehouses);
        var row=balance("2","11",10,3,0);var rows=new ArrayList<>(List.of(row));
        selectWarehouse(warehouses,List.of(new Demand("11",2)),rows);
        assertThat(warehouses).isEqualTo(original);assertThat(rows).containsExactly(row);assertThat(row.reserved()).isEqualTo(3);
    }
}
