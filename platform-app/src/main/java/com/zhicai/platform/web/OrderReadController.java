package com.zhicai.platform.web;

import com.zhicai.platform.catalog.CatalogModels.PageData;
import com.zhicai.platform.common.ApiResult;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.trade.OrderModels;
import com.zhicai.platform.trade.OrderReadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderReadController {
    private final OrderReadService orders;
    public OrderReadController(OrderReadService orders){this.orders=orders;}

    @GetMapping
    ApiResult<PageData<OrderModels.Order>> list(@AuthenticationPrincipal AccountView actor,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(required=false) String state,HttpServletRequest request){
        return ApiResult.ok(orders.list(actor.id(),page,size,state),TraceFilter.id(request));
    }
    @GetMapping("/{id}")
    ApiResult<OrderModels.Order> find(@AuthenticationPrincipal AccountView actor,@PathVariable String id,
        HttpServletRequest request){
        return ApiResult.ok(orders.find(actor.id(),id),TraceFilter.id(request));
    }
}
