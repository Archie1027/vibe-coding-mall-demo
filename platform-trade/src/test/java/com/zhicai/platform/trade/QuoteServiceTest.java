package com.zhicai.platform.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import java.io.IOException;
import com.zhicai.platform.catalog.CheckoutCatalogService;
import com.zhicai.platform.catalog.CatalogModels.Spec;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.inventory.InventoryAvailabilityService;
import com.zhicai.platform.inventory.StockPolicy;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.zhicai.platform.trade.QuoteModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuoteServiceTest {
    final QuoteMapper mapper=mock(QuoteMapper.class);
    final AddressMapper addresses=mock(AddressMapper.class);
    final CheckoutCatalogService catalog=mock(CheckoutCatalogService.class);
    final InventoryAvailabilityService inventory=mock(InventoryAvailabilityService.class);
    // The application supplies Boot's JavaTime module. This module keeps this isolated trade test
    // independent of the web starter while exercising actual snapshot JSON round trips.
    static ObjectMapper fixtureJson(){
        var time=new SimpleModule();
        time.addSerializer(Instant.class,new JsonSerializer<Instant>(){
            @Override public void serialize(Instant value,JsonGenerator out,SerializerProvider provider)throws IOException{out.writeString(value.toString());}
        });
        time.addDeserializer(Instant.class,new JsonDeserializer<Instant>(){
            @Override public Instant deserialize(JsonParser in,DeserializationContext context)throws IOException{return Instant.parse(in.getValueAsString());}
        });
        return new ObjectMapper().registerModule(time).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    final ObjectMapper json=fixtureJson();
    final Instant now=Instant.parse("2026-10-25T01:02:03.123456789Z");
    final QuoteService service=new QuoteService(mapper,addresses,catalog,inventory,json,Clock.fixed(now,ZoneOffset.UTC));
    final TradeModels.Address address=new TradeModels.Address("2","收件人","10000000008","江苏","南京","鼓楼","地址",true,7);
    @BeforeEach void setup(){
        transaction(true,TransactionDefinition.ISOLATION_READ_COMMITTED,false);
        when(addresses.find("1","2")).thenReturn(address);
        when(catalog.skus(anyList())).thenReturn(Map.of("3",sku("100")));
        when(inventory.selectWarehouse(anyList())).thenReturn("4");
        when(mapper.insert(any())).thenAnswer(call->{((QuoteMapper.NewQuote)call.getArgument(0)).id="5";return 1;});
    }
    @AfterEach void clear(){TransactionSynchronizationManager.clear();}
    CheckoutCatalogService.CheckoutSku sku(String price){return new CheckoutCatalogService.CheckoutSku("3","6","当前商品","说明",true,"7","分类","8","品牌","CODE",List.of(new Spec("颜色","蓝")),price,11,12,13,14);}
    Request request(){return new Request("2",List.of(new Item("3",2)),null);}
    void error(Runnable call,int status,String code){assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(status);assertThat(e.code()).isEqualTo(code);});}
    void noIo(){verifyNoInteractions(mapper,addresses,catalog,inventory);}
    static void transaction(boolean active,Integer isolation,boolean readOnly){TransactionSynchronizationManager.setActualTransactionActive(active);TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);}

    @Test void createsImmutableVersionedSnapshotWithExactServerAmountsAndFiveMinuteExpiry() throws Exception {
        var quote=service.create("1",new Request("2",List.of(new Item("3",2),new Item("3",3)),null));
        assertThat(quote.id()).isEqualTo("5");assertThat(quote.warehouseId()).isEqualTo("4");
        assertThat(quote.grossMinor()).isEqualTo("500");assertThat(quote.payableMinor()).isEqualTo("500");
        assertThat(quote.discountMinor()).isEqualTo("0");assertThat(quote.shippingMinor()).isEqualTo("0");
        assertThat(quote.items()).hasSize(1);assertThat(quote.items().getFirst().quantity()).isEqualTo(5);
        assertThat(quote.expiresAt()).isEqualTo(Instant.parse("2026-10-25T01:07:03.123Z"));
        var capture=org.mockito.ArgumentCaptor.forClass(QuoteMapper.NewQuote.class);verify(mapper).insert(capture.capture());
        var draft=capture.getValue();var snapshot=json.readValue(draft.snapshotJson,Snapshot.class);
        assertThat(snapshot.formatVersion()).isEqualTo(1);assertThat(snapshot.owner()).isEqualTo("1");
        assertThat(snapshot.addressId()).isEqualTo("2");assertThat(snapshot.address()).isEqualTo(address);
        assertThat(snapshot.warehouseId()).isEqualTo("4");assertThat(snapshot.createdAt()).isEqualTo(now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(snapshot.expiresAt()).isEqualTo(quote.expiresAt());assertThat(draft.createdAt).isEqualTo(snapshot.createdAt());
        assertThat(draft.expiresAt).isEqualTo(snapshot.expiresAt());assertThat(draft.owner).isEqualTo("1");assertThat(draft.warehouseId).isEqualTo("4");
        var saved=snapshot.items().getFirst();assertThat(saved.catalog()).isEqualTo(sku("100"));assertThat(saved.amounts()).isEqualTo(quote.items().getFirst());
        assertThat(saved.catalog().productVersion()).isEqualTo(12);assertThat(saved.catalog().categoryVersion()).isEqualTo(13);assertThat(saved.catalog().brandVersion()).isEqualTo(14);
        assertThat(json.readValue(json.writeValueAsString(snapshot),Snapshot.class)).isEqualTo(snapshot);
        assertThat(json.readTree(json.writeValueAsString(quote)).get("expiresAt").asText()).isEqualTo("2026-10-25T01:07:03.123Z");
        assertThatThrownBy(()->quote.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->saved.catalog().specs().clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(catalog).skus(List.of("3"));verify(inventory).selectWarehouse(List.of(new StockPolicy.Demand("3",5)));
    }
    @Test void callerMutationCannotChangeRequestOrSnapshot() {
        var inputs=new ArrayList<>(List.of(new Item("3",2)));var input=new Request("2",inputs,null);inputs.clear();
        assertThat(service.create("1",input).items().getFirst().quantity()).isEqualTo(2);
        var specs=new ArrayList<>(List.of(new Spec("颜色","蓝")));var original=sku("100");
        var copy=new CheckoutCatalogService.CheckoutSku(original.skuId(),original.productId(),original.productName(),original.productDescription(),original.productFeatured(),original.categoryId(),original.categoryName(),original.brandId(),original.brandName(),original.skuCode(),specs,original.priceMinor(),11,12,13,14);
        specs.clear();assertThat(copy.specs()).hasSize(1);
    }
    @Test void invalidFieldsAndMergedQuantityFailBeforeAnyIo(){
        for(String owner:Arrays.asList(null,"0","01","1 OR 1=1"))error(()->service.create(owner,request()),400,"VALIDATION_ERROR");
        for(var input:Arrays.asList(null,new Request("0",List.of(new Item("3",1)),null),new Request("2",null,null),new Request("2",List.of(),null),new Request("2",Collections.singletonList(null),null),new Request("2",List.of(new Item("0",1)),null),new Request("2",List.of(new Item("3",null)),null),new Request("2",List.of(new Item("3",100)),null),new Request("2",List.of(new Item("3",50),new Item("3",50)),null),new Request("2",Collections.nCopies(101,new Item("3",1)),null),new Request("2",List.of(new Item("3",1)),"bad")))error(()->service.create("1",input),400,"VALIDATION_ERROR");
        noIo();
    }
    @Test void requiresActualWritableExplicitReadCommittedBeforeAnyIo(){
        for(Integer isolation:Arrays.asList(null,TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_SERIALIZABLE,TransactionDefinition.ISOLATION_READ_UNCOMMITTED)){
            transaction(true,isolation,false);assertThatThrownBy(()->service.create("1",request())).isInstanceOf(IllegalTransactionStateException.class);
        }
        transaction(false,TransactionDefinition.ISOLATION_READ_COMMITTED,false);assertThatThrownBy(()->service.create("1",request())).isInstanceOf(IllegalTransactionStateException.class);
        transaction(true,TransactionDefinition.ISOLATION_READ_COMMITTED,true);assertThatThrownBy(()->service.create("1",request())).isInstanceOf(IllegalTransactionStateException.class);noIo();
    }
    @Test void mandatoryTransactionDoesNotRegisterAnApplicationBean() throws Exception {
        assertThat(QuoteService.class.getAnnotation(org.springframework.stereotype.Service.class)).isNull();
        assertThat(QuoteService.class.getMethod("create",String.class,Request.class).getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }
    @Test void validCouponFailsExplicitlyBeforeAnyIo(){error(()->service.create("1",new Request("2",List.of(new Item("3",1)),"9")),503,"DEPENDENCY_UNAVAILABLE");noIo();}
    @Test void unownedAddressIsNotFoundWithoutCatalogOrInsert(){when(addresses.find("1","2")).thenReturn(null);error(()->service.create("1",request()),404,"NOT_FOUND");verifyNoInteractions(catalog,inventory,mapper);}
    @Test void missingOrDisabledCurrentSkuNeverWritesQuote(){when(catalog.skus(anyList())).thenReturn(Map.of());error(()->service.create("1",request()),409,"SKU_NOT_AVAILABLE");verifyNoInteractions(inventory,mapper);}
    @Test void unavailableSingleWarehouseNeverWritesQuote(){when(inventory.selectWarehouse(anyList())).thenThrow(new ApiException(409,"NO_SINGLE_WAREHOUSE","无单仓"));error(()->service.create("1",request()),409,"NO_SINGLE_WAREHOUSE");verifyNoInteractions(mapper);}
    @Test void exactMaximumMoneyAcceptedAndOverflowDoesNotWrite(){
        when(catalog.skus(anyList())).thenReturn(Map.of("3",sku("999999999999999999")));
        assertThat(service.create("1",new Request("2",List.of(new Item("3",1)),null)).payableMinor()).isEqualTo("999999999999999999");
        clearInvocations(mapper,inventory);error(()->service.create("1",request()),422,"AMOUNT_INVALID");verifyNoInteractions(mapper,inventory);
    }
    @Test void nonPositiveAndMalformedStoredMoneyCannotProduceQuote(){
        for(String price:List.of("0","-1","1000000000000000000","9223372036854775808","1.5")){
            when(catalog.skus(anyList())).thenReturn(Map.of("3",sku(price)));error(()->service.create("1",request()),422,"AMOUNT_INVALID");
        }verifyNoInteractions(mapper,inventory);
    }
    @Test void missingGeneratedIdentityOrFailedInsertAborts(){
        doReturn(0).when(mapper).insert(any());assertThatThrownBy(()->service.create("1",request())).isInstanceOf(IllegalStateException.class);
        doReturn(1).when(mapper).insert(any());assertThatThrownBy(()->service.create("1",request())).isInstanceOf(IllegalStateException.class);
    }
}
