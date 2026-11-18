package com.zhicai.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.trade.MockPaymentService;
import com.zhicai.platform.write.CatalogWriteExecutor;
import com.zhicai.platform.write.MockPaymentApplication;
import com.zhicai.platform.write.MockPaymentConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile(MockPaymentConfiguration.PROFILE)
@ConditionalOnProperty(name = MockPaymentConfiguration.PROPERTY, havingValue = "true")
public class MockPaymentController {
    private final MockPaymentApplication payments;

    public MockPaymentController(MockPaymentApplication payments) {
        this.payments = payments;
    }

    @PostMapping(MockPaymentApplication.START_ROUTE)
    ResponseEntity<String> start(@RequestBody JsonNode body, @AuthenticationPrincipal AccountView actor,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        requireStrings(body, "orderId", "channel");
        return response(payments.start(actor, body.get("orderId").textValue(), body.get("channel").textValue(), key, TraceFilter.id(request)));
    }

    @PostMapping(MockPaymentApplication.SUCCESS_ROUTE)
    ResponseEntity<String> success(@PathVariable String id, @RequestBody JsonNode body, @AuthenticationPrincipal AccountView actor,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        requireStrings(body, "eventId", "result", "channelTransactionId", "amountMinor", "currency");
        var event = new MockPaymentService.Success(body.get("eventId").textValue(), body.get("channelTransactionId").textValue(),
            body.get("amountMinor").textValue(), body.get("currency").textValue());
        return response(payments.success(actor, id, body.get("result").textValue(), event, key, TraceFilter.id(request)));
    }

    private static void requireStrings(JsonNode body, String... fields) {
        if (body == null || !body.isObject() || body.size() != fields.length
            || Arrays.stream(fields).anyMatch(field -> !body.path(field).isTextual()))
            throw new ApiException(400, "VALIDATION_ERROR", "模拟支付请求字段不合法");
    }

    private static ResponseEntity<String> response(CatalogWriteExecutor.Response result) {
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}
