package com.zhicai.platform.trade;

import com.zhicai.platform.catalog.CatalogModels.PageData;
import com.zhicai.platform.catalog.CatalogModels.CartSku;
import com.zhicai.platform.catalog.CatalogQueryService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.CustomerLockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import static com.zhicai.platform.trade.TradeModels.*;

@Service
public class CartService {
    private final CartMapper mapper;
    private final CustomerLockService locks;
    private final CatalogQueryService catalog;
    public CartService(CartMapper mapper, CustomerLockService locks, CatalogQueryService catalog) { this.mapper = mapper; this.locks = locks; this.catalog = catalog; }
    public static void validateAdd(String skuId, Integer quantity) { TradeValidation.id(skuId); TradeValidation.quantity(quantity); }
    public static void validateUpdate(String id, Integer quantity, Boolean selected) {
        TradeValidation.id(id); TradeValidation.quantity(quantity);
        if (selected == null) TradeValidation.bad("勾选状态不能为空");
    }
    @Transactional(readOnly = true)
    public PageData<CartLine> list(String owner, int page, int size) {
        long offset = TradeValidation.offset(page, size);
        var rows = mapper.list(owner, offset, size);
        Map<String, CartSku> skus = rows.isEmpty() ? Map.of() : catalog.cartSkus(rows.stream().map(CartMapper.Row::skuId).toList());
        return new PageData<>(rows.stream().map(row -> line(row, skus.get(row.skuId()))).toList(), page, size, mapper.count(owner));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public CartChange add(String owner, String skuId, Integer quantity) {
        validateAdd(skuId, quantity); locks.lock(owner);
        var before = mapper.findBySku(owner, skuId);
        var sku = catalog.cartSkus(List.of(skuId)).get(skuId); requireAvailable(sku);
        if (before != null) {
            int merged = before.quantity() + quantity;
            if (merged > 99) throw new ApiException(409, "CART_QUANTITY_EXCEEDED", "同一SKU数量合计不能超过99");
            if (mapper.update(owner, before.id(), merged, before.selected()) != 1) throw TradeValidation.notFound("购物车行");
            return new CartChange(line(before, sku), line(new CartMapper.Row(before.id(), skuId, merged, before.selected()), sku));
        }
        var draft = new CartMapper.NewItem(owner, skuId, quantity);
        if (mapper.insert(draft) != 1 || draft.id == null) throw new IllegalStateException("Cart insert did not return identity");
        return new CartChange(null, line(new CartMapper.Row(draft.id.toString(), skuId, quantity, true), sku));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public CartChange update(String owner, String id, Integer quantity, Boolean selected) {
        validateUpdate(id, quantity, selected); locks.lock(owner);
        var before = require(owner, id);
        var sku = catalog.cartSkus(List.of(before.skuId())).get(before.skuId());
        if (selected) requireAvailable(sku);
        if (mapper.update(owner, id, quantity, selected) != 1) throw TradeValidation.notFound("购物车行");
        return new CartChange(line(before, sku), line(new CartMapper.Row(id, before.skuId(), quantity, selected), sku));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public CartChange delete(String owner, String id) {
        TradeValidation.id(id); locks.lock(owner); var before = require(owner, id);
        var sku = catalog.cartSkus(List.of(before.skuId())).get(before.skuId());
        if (mapper.delete(owner, id) != 1) throw TradeValidation.notFound("购物车行");
        return new CartChange(line(before, sku), null);
    }
    private CartMapper.Row require(String owner, String id) {
        var row = mapper.find(owner, id);
        if (row == null) throw TradeValidation.notFound("购物车行");
        return row;
    }
    private static void requireAvailable(CartSku sku) {
        if (sku == null || !sku.valid()) throw new ApiException(409, "SKU_NOT_AVAILABLE", sku == null ? "SKU不存在" : sku.invalidReason());
    }
    private CartLine line(CartMapper.Row row, CartSku sku) {
        // A missing current catalog record remains removable and never receives fabricated sale information.
        if (sku == null) return new CartLine(row.id(), row.skuId(), "商品不存在", List.of(), "0", row.quantity(), row.selected(), false, "SKU不存在", null);
        return new CartLine(row.id(), row.skuId(), sku.productName(), sku.specs(), sku.priceMinor(), row.quantity(), row.selected(), sku.valid(), sku.invalidReason(), sku.imageUrl());
    }
}
