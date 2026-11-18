package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountType;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.trade.MockPaymentService;

public class MockPaymentApplication {
    public static final String START_ROUTE = "/api/v1/payments";
    public static final String SUCCESS_ROUTE = "/api/v1/admin/payments/{id}/mock-events";
    public static final String MOCK_PERMISSION = "system:write";
    private final MockPaymentService service;
    private final CatalogWriteExecutor customerWrites, staffWrites;
    private final ObjectMapper json;
    private final WriteRequestFingerprint fingerprints;

    public MockPaymentApplication(MockPaymentService service, CatalogWriteExecutor customerWrites,
        CatalogWriteExecutor staffWrites, ObjectMapper json) {
        this.service = service;
        this.customerWrites = customerWrites;
        this.staffWrites = staffWrites;
        this.json = json;
        this.fingerprints = new WriteRequestFingerprint(json);
    }

    public CatalogWriteExecutor.Response start(AccountView actor, String orderId, String channel, String key, String trace) {
        authorise(actor, AccountType.CUSTOMER);
        checkId(orderId);
        if (!"MOCK".equals(channel)) throw new ApiException(400, "VALIDATION_ERROR", "仅支持MOCK模拟支付");
        fingerprints.keyHash(key);
        var body = json.createObjectNode().put("orderId", orderId).put("channel", channel);
        return customerWrites.execute(new CatalogWriteExecutor.Request(actor.id(), "POST", START_ROUTE, key, body, trace),
            () -> outcome(201, service.start(actor.id(), orderId), "PAYMENT_CREATE"));
    }

    public CatalogWriteExecutor.Response success(AccountView actor, String paymentId, String result,
        MockPaymentService.Success event, String key, String trace) {
        authorise(actor, AccountType.STAFF);
        checkId(paymentId);
        if (!"SUCCEEDED".equals(result)) throw new ApiException(400, "VALIDATION_ERROR", "本批仅支持模拟成功事件");
        MockPaymentService.validateSuccess(event);
        fingerprints.keyHash(key);
        var body = json.createObjectNode().put("result", result).put("eventId", event.eventId())
            .put("channelTransactionId", event.transactionId()).put("amountMinor", event.amountMinor()).put("currency", event.currency());
        return staffWrites.execute(new CatalogWriteExecutor.Request(actor.id(), "POST", successRoute(paymentId), key, body, trace),
            () -> outcome(200, service.acceptSuccess(paymentId, event), "PAYMENT_MOCK_SUCCESS"));
    }

    static String successRoute(String id) {
        return SUCCESS_ROUTE.replace("{id}", id);
    }

    private CatalogWriteExecutor.Outcome outcome(int status, MockPaymentService.Result payment, String action) {
        var after = json.createObjectNode().put("id", payment.id()).put("state", payment.state());
        return new CatalogWriteExecutor.Outcome(status, payment,
            new CatalogWriteExecutor.Audit(action, "PAYMENT", payment.id(), null, after));
    }

    private static void authorise(AccountView actor, AccountType type) {
        if (actor == null) throw ApiException.unauthenticated();
        if (actor.accountType() != type || (type == AccountType.STAFF && !actor.permissions().contains(MOCK_PERMISSION)))
            throw new ApiException(403, "FORBIDDEN", "没有当前接口权限");
    }

    private static void checkId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}"))
            throw new ApiException(400, "VALIDATION_ERROR", "ID不合法");
    }
}
