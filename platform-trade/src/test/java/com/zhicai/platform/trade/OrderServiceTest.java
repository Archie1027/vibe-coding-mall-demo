package com.zhicai.platform.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.CheckoutCatalogService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.inventory.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    final OrderMapper mapper=mock(OrderMapper.class);
    final CheckoutCatalogService catalog=mock(CheckoutCatalogService.class);
    final InventoryAvailabilityService availability=mock(InventoryAvailabilityService.class);
    final InventoryReservationService reservations=mock(InventoryReservationService.class);
    final ObjectMapper json=QuoteServiceTest.fixtureJson();
    final Instant now=Instant.parse("2026-10-26T01:02:03.123456789Z");
    final Clock clock=mock(Clock.class);
    final OrderService service=new OrderService(mapper,catalog,availability,reservations,json,clock);
    final TradeModels.Address address=new TradeModels.Address("2","收件人","10000000008","江苏","南京","鼓楼","地址",true,7);
    CheckoutCatalogService.CheckoutSku sku(){return new CheckoutCatalogService.CheckoutSku("3","6","原商品","说明",true,"7","分类","8","品牌","CODE",List.of(new com.zhicai.platform.catalog.CatalogModels.Spec("颜色","蓝")),"100",11,12,13,14);}
    QuoteModels.Snapshot snapshot(){return new QuoteModels.Snapshot(1,"1","2",address,"4",List.of(new QuoteModels.SnapshotLine(sku(),new QuoteModels.Line("3","原商品",sku().specs(),2,"100","200","0","200"))),"200","0","0","200",null,now.minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),now.plusSeconds(270).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));}
    OrderMapper.LockedQuote row(QuoteModels.Snapshot snapshot)throws Exception{return new OrderMapper.LockedQuote("5","1","4",json.writeValueAsString(snapshot),snapshot.expiresAt(),null,snapshot.createdAt());}
    @BeforeEach void setup()throws Exception{
        QuoteServiceTest.transaction(true,TransactionDefinition.ISOLATION_READ_COMMITTED,false);
        when(clock.instant()).thenReturn(now);
        when(mapper.lockQuote("1","5")).thenReturn(row(snapshot()));
        when(catalog.lockOrderEvidence(List.of("3"))).thenReturn(new CheckoutCatalogService.OrderEvidence(Map.of("3",sku()),Map.of("6",new CheckoutCatalogService.ImageSnapshot("9","products/frozen.png","image/png",100))));
        when(availability.selectWarehouse(anyList())).thenReturn("4");
        when(mapper.insertOrder(any())).thenAnswer(call->{((OrderMapper.NewOrder)call.getArgument(0)).id="10";return 1;});
        when(mapper.insertItem(any())).thenAnswer(call->{((OrderMapper.NewItem)call.getArgument(0)).id="11";return 1;});
        when(mapper.markQuoteUsed(anyString(),anyString(),anyString(),any())).thenReturn(1);
    }
    @AfterEach void clear(){TransactionSynchronizationManager.clear();}
    OrderModels.Order create(){return service.create("1",new OrderModels.Create("5",""),"order:1");}
    void error(Runnable action,int status,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(status);assertThat(e.code()).isEqualTo(code);});}
    void noWrites(){verify(mapper,never()).insertOrder(any());verify(mapper,never()).insertItem(any());verify(mapper,never()).markQuoteUsed(any(),any(),any(),any());verifyNoInteractions(reservations);}

    @Test void validatesBeforeAnyIo(){
        for(var owner:Arrays.asList(null,"0","01","1 OR 1=1"))error(()->service.create(owner,new OrderModels.Create("5",null),"trace"),400,"VALIDATION_ERROR");
        for(var request:Arrays.asList(null,new OrderModels.Create("0",null),new OrderModels.Create("01",null),new OrderModels.Create("5","😀".repeat(201))))error(()->service.create("1",request,"trace"),400,"VALIDATION_ERROR");
        for(var trace:Arrays.asList(null,"","has space","x".repeat(65)))error(()->service.create("1",new OrderModels.Create("5",null),trace),400,"VALIDATION_ERROR");
        verifyNoInteractions(mapper,catalog,availability,reservations);
    }
    @Test void requiresActualWritableExplicitReadCommittedBeforeMapper(){
        for(Integer isolation:Arrays.asList(null,TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_SERIALIZABLE,TransactionDefinition.ISOLATION_READ_UNCOMMITTED)){
            QuoteServiceTest.transaction(true,isolation,false);assertThatThrownBy(this::create).isInstanceOf(IllegalTransactionStateException.class);
        }
        QuoteServiceTest.transaction(false,TransactionDefinition.ISOLATION_READ_COMMITTED,false);assertThatThrownBy(this::create).isInstanceOf(IllegalTransactionStateException.class);
        QuoteServiceTest.transaction(true,TransactionDefinition.ISOLATION_READ_COMMITTED,true);assertThatThrownBy(this::create).isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(mapper,catalog,availability,reservations);
    }
    @Test void remainsInternalMandatoryService()throws Exception{
        assertThat(OrderService.class.getAnnotation(org.springframework.stereotype.Service.class)).isNull();
        assertThat(OrderService.class.getMethod("create",String.class,OrderModels.Create.class,String.class).getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }
    @Test void missingAndOtherOwnerAreNotFound()throws Exception{
        when(mapper.lockQuote("1","5")).thenReturn(null);error(this::create,404,"NOT_FOUND");
        var row=row(snapshot());when(mapper.lockQuote("1","5")).thenReturn(new OrderMapper.LockedQuote(row.id(),"99",row.warehouseId(),row.snapshotJson(),row.expiresAt(),null,row.createdAt()));error(this::create,404,"NOT_FOUND");noWrites();verifyNoInteractions(catalog,availability);
    }
    @Test void usedQuoteAndExpiredQuoteFailBeforeCatalog()throws Exception{
        var row=row(snapshot());when(mapper.lockQuote("1","5")).thenReturn(new OrderMapper.LockedQuote(row.id(),row.owner(),row.warehouseId(),row.snapshotJson(),row.expiresAt(),"10",row.createdAt()));error(this::create,409,"STATE_CONFLICT");
        when(mapper.lockQuote("1","5")).thenReturn(new OrderMapper.LockedQuote(row.id(),row.owner(),row.warehouseId(),row.snapshotJson(),now,null,row.createdAt()));error(this::create,409,"QUOTE_EXPIRED");noWrites();verifyNoInteractions(catalog,availability);
    }
    @Test void corruptedAndUnrecognizedSnapshotNeverWrites()throws Exception{
        var row=row(snapshot());
        for(var body:List.of("{}","null","{bad",row.snapshotJson().replace("\"formatVersion\":1","\"formatVersion\":2"),row.snapshotJson().replace("\"items\":[", "\"items\":[null,"),row.snapshotJson().replace("\"skuId\":\"3\"","\"skuId\":null"),row.snapshotJson().replace("\"grossMinor\":\"200\"","\"grossMinor\":null"))){
            when(mapper.lockQuote("1","5")).thenReturn(new OrderMapper.LockedQuote("5","1","4",body,row.expiresAt(),null,row.createdAt()));assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);
        }noWrites();verifyNoInteractions(catalog,availability);
    }
    @Test void everyQuoteMetadataBindingMustMatch()throws Exception{
        var row=row(snapshot());
        var rows=List.of(new OrderMapper.LockedQuote("6","1","4",row.snapshotJson(),row.expiresAt(),null,row.createdAt()),new OrderMapper.LockedQuote("5","1","7",row.snapshotJson(),row.expiresAt(),null,row.createdAt()),new OrderMapper.LockedQuote("5","1","4",row.snapshotJson(),row.expiresAt().plusMillis(1),null,row.createdAt()),new OrderMapper.LockedQuote("5","1","4",row.snapshotJson(),row.expiresAt(),null,row.createdAt().plusMillis(1)),new OrderMapper.LockedQuote("5","1","4",row.snapshotJson().replace("\"owner\":\"1\"","\"owner\":\"2\""),row.expiresAt(),null,row.createdAt()),new OrderMapper.LockedQuote("5","1","4",row.snapshotJson().replace("\"addressId\":\"2\"","\"addressId\":\"9\""),row.expiresAt(),null,row.createdAt()));
        for(var invalid:rows){when(mapper.lockQuote("1","5")).thenReturn(invalid);assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);}noWrites();verifyNoInteractions(catalog,availability);
    }
    @Test void persistedCouponFailsExplicitlyBeforeCatalog()throws Exception{
        var row=row(snapshot());when(mapper.lockQuote("1","5")).thenReturn(new OrderMapper.LockedQuote("5","1","4",row.snapshotJson().replace("\"createdAt\"","\"couponId\":\"9\",\"createdAt\""),row.expiresAt(),null,row.createdAt()));error(this::create,503,"DEPENDENCY_UNAVAILABLE");noWrites();verifyNoInteractions(catalog,availability);
    }
    @Test void missingOrChangedSkuIsCheckedBeforeAvailability()throws Exception{
        when(catalog.lockOrderEvidence(anyList())).thenReturn(new CheckoutCatalogService.OrderEvidence(Map.of(),Map.of()));error(this::create,409,"QUOTE_CHANGED");
        var changed=json.readValue(json.writeValueAsString(sku()).replace("\"skuVersion\":11","\"skuVersion\":12"),CheckoutCatalogService.CheckoutSku.class);
        when(catalog.lockOrderEvidence(anyList())).thenReturn(new CheckoutCatalogService.OrderEvidence(Map.of("3",changed),Map.of()));error(this::create,409,"QUOTE_CHANGED");
        var priced=json.readValue(json.writeValueAsString(sku()).replace("\"priceMinor\":\"100\"","\"priceMinor\":\"101\""),CheckoutCatalogService.CheckoutSku.class);
        when(catalog.lockOrderEvidence(anyList())).thenReturn(new CheckoutCatalogService.OrderEvidence(Map.of("3",priced),Map.of()));error(this::create,409,"QUOTE_CHANGED");verifyNoInteractions(availability);noWrites();
    }
    @Test void missingImageAndChangedWarehouseNeverInsert(){
        when(catalog.lockOrderEvidence(anyList())).thenReturn(new CheckoutCatalogService.OrderEvidence(Map.of("3",sku()),Map.of()));error(this::create,409,"QUOTE_CHANGED");verifyNoInteractions(availability);noWrites();
    }
    @Test void changedWarehouseNeverInsert(){when(availability.selectWarehouse(anyList())).thenReturn("8");error(this::create,409,"QUOTE_CHANGED");noWrites();}
    @Test void availabilityFailureIsPropagated(){var failure=new ApiException(409,"NO_SINGLE_WAREHOUSE","no warehouse");when(availability.selectWarehouse(anyList())).thenThrow(failure);assertThatThrownBy(this::create).isSameAs(failure);noWrites();}
    @Test void freezesServerAmountsAndHistoryWithExactInventoryReferences()throws Exception{
        var order=service.create("1",new OrderModels.Create("5","😀".repeat(200)),"order:1");
        assertThat(order.id()).isEqualTo("10");assertThat(order.orderNo()).matches("ZC[0-9a-f]{32}");assertThat(order.customerId()).isEqualTo("1");assertThat(order.warehouseId()).isEqualTo("4");assertThat(order.state()).isEqualTo("UNPAID");assertThat(order.version()).isZero();
        assertThat(order.grossMinor()).isEqualTo("200");assertThat(order.discountMinor()).isEqualTo("0");assertThat(order.shippingMinor()).isEqualTo("0");assertThat(order.payableMinor()).isEqualTo("200");assertThat(order.refundedMinor()).isEqualTo("0");
        assertThat(order.createdAt()).isEqualTo(Instant.parse("2026-10-26T01:02:03.123Z"));assertThat(order.expiresAt()).isEqualTo(order.createdAt().plusSeconds(1800));
        assertThat(order.addressSnapshot()).isEqualTo(new TradeModels.AddressInput(address.recipient(),address.phone(),address.province(),address.city(),address.district(),address.detail(),true));
        var item=order.items().getFirst();assertThat(item.id()).isEqualTo("11");assertThat(item.skuId()).isEqualTo("3");assertThat(item.productName()).isEqualTo("原商品");assertThat(item.specs()).isEqualTo(sku().specs());assertThat(item.quantity()).isEqualTo(2);assertThat(item.unitPriceMinor()).isEqualTo("100");assertThat(item.grossMinor()).isEqualTo("200");assertThat(item.payableMinor()).isEqualTo("200");assertThat(item.returnedQuantity()).isZero();assertThat(item.refundedMinor()).isEqualTo("0");
        assertThatThrownBy(()->order.items().clear()).isInstanceOf(UnsupportedOperationException.class);assertThatThrownBy(()->item.specs().clear()).isInstanceOf(UnsupportedOperationException.class);
        var orderCapture=org.mockito.ArgumentCaptor.forClass(OrderMapper.NewOrder.class);var itemCapture=org.mockito.ArgumentCaptor.forClass(OrderMapper.NewItem.class);
        var calls=inOrder(mapper,catalog,availability,reservations);calls.verify(mapper).lockQuote("1","5");calls.verify(catalog).lockOrderEvidence(List.of("3"));calls.verify(availability).selectWarehouse(List.of(new StockPolicy.Demand("3",2)));calls.verify(mapper).insertOrder(orderCapture.capture());calls.verify(mapper).insertItem(itemCapture.capture());calls.verify(reservations).hold(new InventoryReservationService.Command("10","4",List.of(new InventoryReservationService.Line("11","3",2)),"1","order:1"));calls.verify(mapper).markQuoteUsed("1","5","10",now);
        var saved=orderCapture.getValue();assertThat(saved.remark).isEqualTo("😀".repeat(200));assertThat(saved.orderNo).isEqualTo(order.orderNo());assertThat(saved.createdAt).isEqualTo(order.createdAt());assertThat(saved.expiresAt).isEqualTo(order.expiresAt());assertThat(json.readTree(saved.addressSnapshotJson).has("id")).isFalse();assertThat(json.readTree(saved.addressSnapshotJson).has("version")).isFalse();
        var savedItem=itemCapture.getValue();assertThat(savedItem.orderId).isEqualTo("10");assertThat(savedItem.lineNo).isZero();assertThat(savedItem.productId).isEqualTo("6");assertThat(json.readTree(savedItem.specsSnapshotJson)).isEqualTo(json.valueToTree(sku().specs()));assertThat(json.readTree(savedItem.imageSnapshotJson).get("storageKey").asText()).isEqualTo("products/frozen.png");assertThat(json.valueToTree(order).get("items").get(0).has("image")).isFalse();
    }
    @Test void generatedKeysAndAffectedRowsAreMandatory(){
        doReturn(0).when(mapper).insertOrder(any());assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);verifyNoInteractions(reservations);
        doReturn(1).when(mapper).insertOrder(any());assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);verifyNoInteractions(reservations);
    }
    @Test void itemKeyAndAffectedRowsAreMandatory(){
        doReturn(0).when(mapper).insertItem(any());assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);
        doReturn(1).when(mapper).insertItem(any());assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);verifyNoInteractions(reservations);verify(mapper,never()).markQuoteUsed(any(),any(),any(),any());
    }
    @Test void holdFailurePropagatesWithoutMark(){var failure=new IllegalStateException("hold failed");when(reservations.hold(any())).thenThrow(failure);assertThatThrownBy(this::create).isSameAs(failure);verify(mapper,never()).markQuoteUsed(any(),any(),any(),any());}
    @Test void expiryAfterAvailabilityWaitStopsBeforeInsert(){when(clock.instant()).thenReturn(now,now,now.plusSeconds(300));error(this::create,409,"QUOTE_EXPIRED");noWrites();}
    @Test void expiryAfterCatalogWaitStopsBeforeAvailability(){
        var evidence=new CheckoutCatalogService.OrderEvidence(Map.of("3",sku()),Map.of("6",new CheckoutCatalogService.ImageSnapshot("9","frozen.png","image/png",100)));
        when(catalog.lockOrderEvidence(anyList())).thenAnswer(call->{when(clock.instant()).thenReturn(snapshot().expiresAt());return evidence;});
        error(this::create,409,"QUOTE_EXPIRED");verifyNoInteractions(availability);noWrites();
    }
    @Test void expiryAfterHoldStopsBeforeQuoteMark(){when(reservations.hold(any())).thenAnswer(call->{when(clock.instant()).thenReturn(now.plusSeconds(300));return List.of();});error(this::create,409,"QUOTE_EXPIRED");verify(mapper,never()).markQuoteUsed(any(),any(),any(),any());}
    @Test void failedConditionalQuoteMarkPropagatesConflict(){when(mapper.markQuoteUsed(any(),any(),any(),any())).thenReturn(0);error(this::create,409,"STATE_CONFLICT");verify(reservations).hold(any());}
    @Test void unknownInsertFailureIsNotCaughtOrRetried(){var failure=new IllegalStateException("unknown write outcome");doThrow(failure).when(mapper).insertOrder(any());assertThatThrownBy(this::create).isSameAs(failure);verify(mapper,times(1)).insertOrder(any());verifyNoInteractions(reservations);}
    @Test void allLineSerializationFinishesBeforeFirstWrite()throws Exception{
        var failing=spy(json);doThrow(new com.fasterxml.jackson.core.JsonProcessingException("image serialization failed"){}).when(failing).writeValueAsString(isA(CheckoutCatalogService.ImageSnapshot.class));
        var service=new OrderService(mapper,catalog,availability,reservations,failing,clock);
        assertThatThrownBy(()->service.create("1",new OrderModels.Create("5",null),"trace")).isInstanceOf(IllegalStateException.class).hasMessage("Cannot serialize order snapshot");noWrites();
    }
    @Test void maximumIntegerMoneyRemainsAStringWithoutPrecisionLoss()throws Exception{
        var original=row(snapshot());var saved=original.snapshotJson().replace("\"quantity\":2","\"quantity\":1").replace("\"100\"","\"999999999999999999\"").replace("\"200\"","\"999999999999999999\"");
        when(mapper.lockQuote("1","5")).thenReturn(new OrderMapper.LockedQuote("5","1","4",saved,original.expiresAt(),null,original.createdAt()));
        var priced=json.readValue(json.writeValueAsString(sku()).replace("\"100\"","\"999999999999999999\""),CheckoutCatalogService.CheckoutSku.class);
        when(catalog.lockOrderEvidence(anyList())).thenReturn(new CheckoutCatalogService.OrderEvidence(Map.of("3",priced),Map.of("6",new CheckoutCatalogService.ImageSnapshot("9","image.png","image/png",10))));
        var order=create();assertThat(order.payableMinor()).isEqualTo("999999999999999999");assertThat(json.valueToTree(order).get("payableMinor").isTextual()).isTrue();assertThat(order.items().getFirst().unitPriceMinor()).isEqualTo("999999999999999999");
    }
    @Test void quoteLineOrderAndEachGeneratedItemIdentityReachInventoryExactly()throws Exception{
        var first=snapshot().items().getFirst();var secondSku=json.readValue(json.writeValueAsString(sku()).replace("\"skuId\":\"3\"","\"skuId\":\"12\""),CheckoutCatalogService.CheckoutSku.class);
        var second=new QuoteModels.SnapshotLine(secondSku,new QuoteModels.Line("12","原商品",secondSku.specs(),3,"100","300","0","300"));
        var original=snapshot();var quote=new QuoteModels.Snapshot(1,"1","2",address,"4",List.of(second,first),"500","0","0","500",null,original.createdAt(),original.expiresAt());
        when(mapper.lockQuote("1","5")).thenReturn(row(quote));
        when(catalog.lockOrderEvidence(List.of("12","3"))).thenReturn(new CheckoutCatalogService.OrderEvidence(Map.of("3",sku(),"12",secondSku),Map.of("6",new CheckoutCatalogService.ImageSnapshot("9","image.png","image/png",10))));
        var counter=new java.util.concurrent.atomic.AtomicInteger(20);doAnswer(call->{((OrderMapper.NewItem)call.getArgument(0)).id=Integer.toString(counter.incrementAndGet());return 1;}).when(mapper).insertItem(any());
        var order=create();assertThat(order.items()).extracting(OrderModels.Line::skuId).containsExactly("12","3");assertThat(order.items()).extracting(OrderModels.Line::id).containsExactly("21","22");
        verify(availability).selectWarehouse(List.of(new StockPolicy.Demand("12",3),new StockPolicy.Demand("3",2)));
        verify(reservations).hold(new InventoryReservationService.Command("10","4",List.of(new InventoryReservationService.Line("21","12",3),new InventoryReservationService.Line("22","3",2)),"1","order:1"));
        var capture=org.mockito.ArgumentCaptor.forClass(OrderMapper.NewItem.class);verify(mapper,times(2)).insertItem(capture.capture());assertThat(capture.getAllValues()).extracting(item->item.lineNo).containsExactly(0,1);
    }
}
