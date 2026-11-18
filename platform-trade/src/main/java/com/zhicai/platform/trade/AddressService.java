package com.zhicai.platform.trade;

import com.zhicai.platform.catalog.CatalogModels.PageData;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.CustomerLockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.zhicai.platform.trade.TradeModels.*;

@Service
public class AddressService {
    private final AddressMapper mapper;
    private final CustomerLockService locks;
    public AddressService(AddressMapper mapper, CustomerLockService locks) { this.mapper = mapper; this.locks = locks; }
    public static void validate(AddressInput input) {
        if (input == null || !TradeValidation.text(input.recipient(), 50) || input.phone() == null || !input.phone().matches("1[0-9]{10}")
            || !TradeValidation.text(input.province(), 120) || !TradeValidation.text(input.city(), 120)
            || !TradeValidation.text(input.district(), 120) || !TradeValidation.text(input.detail(), 200) || input.isDefault() == null)
            TradeValidation.bad("收件人、手机号、地区、详细地址或默认状态不合法");
    }
    public static void validateUpdate(String id, AddressInput input, Long version) {
        TradeValidation.id(id); validate(input);
        if (version == null || version < 0 || version >= TradeValidation.MAX_SAFE_VERSION) TradeValidation.bad("地址版本不合法");
    }
    @Transactional(readOnly = true)
    public PageData<Address> list(String owner, int page, int size) {
        long offset = TradeValidation.offset(page, size);
        return new PageData<>(mapper.list(owner, offset, size), page, size, mapper.count(owner));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public AddressChange create(String owner, AddressInput input) {
        validate(input); locks.lock(owner);
        if (mapper.count(owner) >= 20) throw new ApiException(409, "ADDRESS_LIMIT_EXCEEDED", "收货地址最多20条");
        if (input.isDefault()) clearDefault(owner, null);
        var draft = new AddressMapper.NewAddress(owner, input);
        if (mapper.insert(draft) != 1 || draft.id == null) throw new IllegalStateException("Address insert did not return identity");
        return new AddressChange(null, address(draft.id.toString(), input, 0));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public AddressChange update(String owner, String id, AddressInput input, Long version) {
        validateUpdate(id, input, version); locks.lock(owner);
        var before = require(owner, id);
        if (before.version() != version) throw TradeValidation.versionConflict();
        if (input.isDefault()) clearDefault(owner, id);
        if (mapper.update(owner, id, input, version) != 1) throw TradeValidation.versionConflict();
        return new AddressChange(before, address(id, input, version + 1));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public AddressChange delete(String owner, String id) {
        TradeValidation.id(id); locks.lock(owner); var before = require(owner, id);
        if (mapper.delete(owner, id) != 1) throw TradeValidation.notFound("地址");
        return new AddressChange(before, null);
    }
    private Address require(String owner, String id) {
        var address = mapper.find(owner, id);
        if (address == null) throw TradeValidation.notFound("地址");
        return address;
    }
    private void clearDefault(String owner, String exceptId) {
        var previous = mapper.findDefault(owner);
        if (previous != null && !previous.id().equals(exceptId) && previous.version() >= TradeValidation.MAX_SAFE_VERSION)
            throw TradeValidation.versionConflict();
        mapper.clearDefault(owner, exceptId);
    }
    private Address address(String id, AddressInput input, long version) {
        return new Address(id, input.recipient(), input.phone(), input.province(), input.city(), input.district(), input.detail(), input.isDefault(), version);
    }
}
