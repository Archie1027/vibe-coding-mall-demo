package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.write.*;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class WritePrimitivesTest {
    final ObjectMapper json = new ObjectMapper();
    final WriteRequestFingerprint fingerprints = new WriteRequestFingerprint(json);
    final String key = Base64.getEncoder().encodeToString(new byte[32]);

    @Test void fieldOrderAndWhitespaceDoNotChangeValidatedJsonFingerprint() throws Exception {
        var a = json.readTree("{\"name\":\"品牌\",\"nested\":{\"b\":2,\"a\":1}}");
        var b = json.readTree("{ \"nested\": {\"a\":1,\"b\":2}, \"name\":\"品牌\" }");
        assertThat(fingerprints.requestHash("STAFF:1", "POST", "/api/v1/admin/brands", a))
            .isEqualTo(fingerprints.requestHash("STAFF:1", "POST", "/api/v1/admin/brands", b));
    }
    @Test void scopeRouteMethodValuesAndArrayOrderRemainSignificant() throws Exception {
        var body = json.readTree("{\"values\":[1,2]}");
        String original = fingerprints.requestHash("STAFF:1", "POST", "/api/v1/admin/brands", body);
        assertThat(fingerprints.requestHash("STAFF:2", "POST", "/api/v1/admin/brands", body)).isNotEqualTo(original);
        assertThat(fingerprints.requestHash("STAFF:1", "PUT", "/api/v1/admin/brands", body)).isNotEqualTo(original);
        assertThat(fingerprints.requestHash("STAFF:1", "POST", "/api/v1/admin/brands/1", body)).isNotEqualTo(original);
        assertThat(fingerprints.requestHash("STAFF:1", "POST", "/api/v1/admin/brands", json.readTree("{\"values\":[2,1]}"))).isNotEqualTo(original);
    }
    @Test void rejectsInvalidKeysAndDoesNotPersistRawKeys() {
        for (String invalid : new String[]{"", "short", "a".repeat(129), "空".repeat(16), "a".repeat(15) + "\n"})
            assertThatThrownBy(() -> fingerprints.keyHash(invalid)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> fingerprints.keyHash(null)).isInstanceOf(ApiException.class);
        assertThat(fingerprints.keyHash("a".repeat(16))).matches("[0-9a-f]{64}");
        assertThat(fingerprints.keyHash("a".repeat(128))).matches("[0-9a-f]{64}");
    }
    @Test void rejectsNonCanonicalRouteOrIdentity() throws Exception {
        var body = json.readTree("{}");
        assertThatThrownBy(() -> fingerprints.requestHash("STAFF:0", "POST", "/api/v1/admin/brands", body)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fingerprints.requestHash("STAFF:1", "POST", "/api/v1/admin/brands?q=1", body)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void replayRoundTripsUtf8WithDifferentCiphertexts() {
        var cipher = new ReplayCipher(key);
        String response = "{\"name\":\"品牌\",\"id\":\"9007199254740993\"}";
        byte[] a = cipher.encrypt(response, "record-1"), b = cipher.encrypt(response, "record-1");
        assertThat(a).isNotEqualTo(b);
        assertThat(cipher.decrypt(a, "record-1")).isEqualTo(response);
    }
    @Test void replayRejectsTamperingWrongRecordWrongKeyAndBadEnvelope() {
        var cipher = new ReplayCipher(key);
        byte[] encrypted = cipher.encrypt("sensitive-response", "record-1");
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "record-2")).isInstanceOf(IllegalStateException.class);
        var other = new ReplayCipher(Base64.getEncoder().encodeToString("a".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> other.decrypt(encrypted, "record-1")).isInstanceOf(IllegalStateException.class);
        encrypted[encrypted.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "record-1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(new byte[28], "record-1")).isInstanceOf(IllegalStateException.class);
    }
    @Test void cipherRejectsInvalidConfigurationWithoutIncludingSecrets() {
        assertThatThrownBy(() -> new ReplayCipher("not-base64-secret")).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("not-base64-secret");
        assertThatThrownBy(() -> new ReplayCipher(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalArgumentException.class);
    }
}
