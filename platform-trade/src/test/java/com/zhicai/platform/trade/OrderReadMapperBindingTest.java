package com.zhicai.platform.trade;

import java.sql.PreparedStatement;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderReadMapperBindingTest {
    record Binding(String sql,PreparedStatement ps,org.apache.ibatis.mapping.MappedStatement statement){}
    Binding bind(String name,Class<?>[] types,Object... args)throws Exception{
        var configuration=new Configuration();
        try(var stream=getClass().getClassLoader().getResourceAsStream("mapper/OrderReadMapper.xml")){
            assertThat(stream).isNotNull();
            new XMLMapperBuilder(stream,configuration,"mapper/OrderReadMapper.xml",configuration.getSqlFragments()).parse();
        }
        var params=new ParamNameResolver(configuration,OrderReadMapper.class.getMethod(name,types)).getNamedParams(args);
        var statement=configuration.getMappedStatement(OrderReadMapper.class.getName()+"."+name);
        var bound=statement.getBoundSql(params);
        var ps=mock(PreparedStatement.class);
        new DefaultParameterHandler(statement,params,bound).setParameters(ps);
        return new Binding(bound.getSql().replaceAll("\\s+"," ").trim(),ps,statement);
    }
    @Test void listScopesOwnerAndStateWithStablePageOrder()throws Exception{
        var b=bind("list",new Class<?>[]{String.class,String.class,long.class,int.class},"7","UNPAID",20L,20);
        assertThat(b.sql()).contains("customer_id=CAST(? AS UNSIGNED)","AND state=?","ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?")
            .doesNotContain("FOR UPDATE","FOR SHARE","${");
        assertThat(b.statement().getResultMaps().getFirst().getType()).isEqualTo(OrderReadMapper.OrderRow.class);
        verify(b.ps()).setString(1,"7");verify(b.ps()).setString(2,"UNPAID");
        verify(b.ps()).setInt(3,20);verify(b.ps()).setLong(4,20L);
    }
    @Test void detailRequiresOwnerAndItemsAreBoundedToSelectedIds()throws Exception{
        var detail=bind("find",new Class<?>[]{String.class,String.class},"7","9007199254740993");
        assertThat(detail.sql()).contains("customer_id=CAST(? AS UNSIGNED)","id=CAST(? AS UNSIGNED)")
            .doesNotContain("FOR UPDATE","${");
        verify(detail.ps()).setString(1,"7");verify(detail.ps()).setString(2,"9007199254740993");
        var lines=bind("lines",new Class<?>[]{List.class},List.of("9007199254740993","9"));
        assertThat(lines.sql()).contains("order_id IN ( CAST(? AS UNSIGNED) , CAST(? AS UNSIGNED) )","ORDER BY order_id,line_no,id")
            .doesNotContain("FOR UPDATE","${");
        assertThat(lines.statement().getResultMaps().getFirst().getType()).isEqualTo(OrderReadMapper.LineRow.class);
        verify(lines.ps()).setString(1,"9007199254740993");verify(lines.ps()).setString(2,"9");
    }
}
