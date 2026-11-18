package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhicai.platform.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/** Fingerprints validated catalog DTOs only; never pass passwords or uploaded file bodies. */
public final class WriteRequestFingerprint {
    private final ObjectMapper json;
    public WriteRequestFingerprint(ObjectMapper json) { this.json = json; }

    public String keyHash(String key) {
        if (key == null || key.length() < 16 || key.length() > 128 || !key.matches("[\\x21-\\x7E]+"))
            throw new ApiException(400, "VALIDATION_ERROR", "Idempotency-Key需为16–128位可见ASCII字符");
        return sha256(key);
    }

    public String requestHash(String actorScope, String method, String canonicalRoute, JsonNode validatedBody) {
        if (actorScope == null || !actorScope.matches("(?:STAFF|CUSTOMER):[1-9][0-9]{0,18}")
            || method == null || !method.matches("POST|PUT|PATCH|DELETE")
            || canonicalRoute == null || !canonicalRoute.matches("/api/v1/[A-Za-z0-9/_-]{1,244}")
            || validatedBody == null)
            throw new IllegalArgumentException("Expected validated identity, write method, canonical route and JSON body");
        return sha256(actorScope + "\n" + method + "\n" + canonicalRoute + "\n" + canonical(validatedBody));
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            for (String name : names) result.set(name, canonical(node.get(name)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode(); node.forEach(child -> result.add(canonical(child))); return result;
        }
        return node;
    }

    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
}
