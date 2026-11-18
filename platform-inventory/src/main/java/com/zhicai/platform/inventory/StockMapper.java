package com.zhicai.platform.inventory;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockMapper {
    record Warehouse(String id, boolean enabled, boolean fulfillmentEnabled) {}
    record Balance(String id, String warehouseId, String skuId, int onHand, int reserved, int quarantine, long version) {}
    record Reservation(String id, String businessLineId, String warehouseId, String skuId, int quantity, String state, long version) {}
    record ReserveEvidence(String businessId, String businessLineId, String warehouseId, String skuId, int quantity) {}
    record Ledger(String businessId, String businessLineId, String warehouseId, String skuId, String action,
                  int onHand, int reservedBefore, int quarantine, int deltaReserved, String actorId, String traceId) {}
    final class NewReservation {
        public String id;
        public final String businessLineId, warehouseId, skuId;
        public final int quantity;
        public NewReservation(String businessLineId, String warehouseId, String skuId, int quantity) {
            this.businessLineId = businessLineId; this.warehouseId = warehouseId; this.skuId = skuId; this.quantity = quantity;
        }
    }

    Warehouse lockWarehouse(@Param("warehouseId") String warehouseId);
    Balance lockBalance(@Param("warehouseId") String warehouseId, @Param("skuId") String skuId);
    Reservation lockReservation(@Param("businessLineId") String businessLineId);
    List<ReserveEvidence> reserveEvidence(@Param("businessLineId") String businessLineId);
    int increaseReserved(@Param("id") String id, @Param("quantity") int quantity, @Param("version") long version);
    int decreaseReserved(@Param("id") String id, @Param("quantity") int quantity, @Param("version") long version);
    int insertReservation(NewReservation reservation);
    int markReleased(@Param("id") String id, @Param("version") long version);
    int insertLedger(Ledger ledger);
}
