package com.zhicai.platform.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderReadServiceTest {
    private final OrderReadMapper mapper=mock(OrderReadMapper.class);
    private final OrderReadService service=new OrderReadService(mapper,new ObjectMapper().findAndRegisterModules());
    private static final Instant AT=Instant.parse("2026-10-27T01:02:03Z");

    @Test void pageHydratesPersistedSnapshotsWithoutCatalogLookups(){
        var row=new OrderReadMapper.OrderRow("9007199254740993","ZC1","7","2","UNPAID",
            "{\"recipient\":\"王同学\",\"phone\":\"10000000007\",\"province\":\"江苏\",\"city\":\"南京\",\"district\":\"玄武\",\"detail\":\"教学楼\",\"isDefault\":true}",
            "300","0","0","300","0",AT.plusSeconds(1800),AT,0);
        var line=new OrderReadMapper.LineRow(row.id(),"8000000000000001",0,"8","历史商品",
            "[{\"name\":\"颜色\",\"value\":\"黑色\"}]",1,"300","300","0","300",0,"0");
        when(mapper.list("7","UNPAID",20,20)).thenReturn(List.of(row));
        when(mapper.lines(List.of(row.id()))).thenReturn(List.of(line));
        when(mapper.count("7","UNPAID")).thenReturn(21L);
        var page=service.list("7",2,20,"UNPAID");
        assertThat(page.total()).isEqualTo(21);
        assertThat(page.items().getFirst().id()).isEqualTo("9007199254740993");
        assertThat(page.items().getFirst().addressSnapshot().recipient()).isEqualTo("王同学");
        assertThat(page.items().getFirst().items().getFirst().productName()).isEqualTo("历史商品");
        verify(mapper).list("7","UNPAID",20,20);
    }

    @Test void absentOrOtherCustomersOrderIsNotFound(){
        assertThatThrownBy(()->service.find("7","9")).isInstanceOf(ApiException.class)
            .hasMessageContaining("订单不存在");
        verify(mapper).find("7","9");
        verify(mapper,never()).lines(anyList());
    }

    @Test void invalidPaginationStateAndIdNeverQueryDatabase(){
        for(var call:List.<Runnable>of(()->service.list("7",0,20,null),()->service.list("7",1,101,null),
            ()->service.list("7",1,20,"SOMETHING"),()->service.find("7","0")))
            assertThatThrownBy(call::run).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
}
