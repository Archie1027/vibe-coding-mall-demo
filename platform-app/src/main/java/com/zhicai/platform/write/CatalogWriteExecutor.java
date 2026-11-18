package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.common.ApiResult;
import com.zhicai.platform.web.ErrorResponder.ErrorBody;
import com.zhicai.platform.identity.AccountType;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Shared transaction/replay engine; actor scope is fixed by server configuration, never by HTTP input. */
public final class CatalogWriteExecutor {
    public record Request(String actorId, String method, String route, String key, JsonNode body, String traceId) {}
    public record Audit(String action, String resourceType, String resourceId, JsonNode before, JsonNode after) {}
    public record Outcome(int status, Object data, Audit audit) {}
    public record Response(int status, String body, boolean replayed) {}
    private record Scope(String actor, String method, String route, String keyHash, String requestHash) {}
    private record Entry(String id, String requestHash, String owner, String state, Integer status,
                         byte[] response, Instant leaseUntil, Instant expiresAt) {}
    private static final String WHERE = " WHERE actor_scope=? AND method=? AND route=? AND key_hash=?";
    private static final String SELECT = "SELECT CAST(id AS CHAR) id,request_hash,owner_token,state,http_status,encrypted_response,lease_until,expires_at FROM idempotency_request";
    private static final Set<String> SNAPSHOT_FIELDS = Set.of("id", "name", "enabled", "version", "state", "categoryId", "parentId", "sort", "brandId", "featured", "productId", "code", "priceMinor", "contentType", "sizeBytes", "fileSha256");
    private static final RowMapper<Entry> ENTRY = (rs, n) -> new Entry(rs.getString("id"), rs.getString("request_hash"), rs.getString("owner_token"), rs.getString("state"),
        rs.getObject("http_status") == null ? null : ((Number) rs.getObject("http_status")).intValue(), rs.getBytes("encrypted_response"), rs.getTimestamp("lease_until").toInstant(), rs.getTimestamp("expires_at").toInstant());
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final ReplayCipher cipher;
    private final WriteRequestFingerprint fingerprints;
    private final Clock clock;
    private final AccountType actorType;
    private static final Set<String> CUSTOMER_SNAPSHOT_FIELDS=Set.of("id","version","isDefault","skuId","quantity","selected","valid","priceMinor");
    private final SQLErrorCodeSQLExceptionTranslator mysqlErrors = new SQLErrorCodeSQLExceptionTranslator("MySQL");

    public CatalogWriteExecutor(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json, ReplayCipher cipher, Clock clock) {
        this(jdbc,manager,json,cipher,clock,AccountType.STAFF);
    }
    public CatalogWriteExecutor(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json, ReplayCipher cipher, Clock clock,AccountType actorType) {
        this.jdbc = jdbc; this.json = json; this.cipher = cipher; this.clock = clock;
        this.actorType=java.util.Objects.requireNonNull(actorType);
        fingerprints = new WriteRequestFingerprint(json);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
    }

    public Response execute(Request request, Supplier<Outcome> operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Write executor must own the outer business transaction");
        // Caller authenticates and validates the typed DTO before entering this executor.
        String actor = actorType.name()+":"+request.actorId();
        Scope scope = new Scope(actor, request.method(), request.route(), fingerprints.keyHash(request.key()),
            fingerprints.requestHash(actor, request.method(), request.route(), request.body()));
        if (request.traceId() == null || !request.traceId().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Validated server trace ID required");
        String owner = UUID.randomUUID().toString();
        Entry claimed = claim(scope, owner);
        if (claimed.state().equals("COMPLETED")) return replay(claimed);
        try {
            return transaction.execute(status -> {
                Entry current = read(scope, true);
                ensureOwner(current, owner);
                Outcome outcome = operation.get();
                if (outcome == null || outcome.status() < 200 || outcome.status() > 299 || outcome.audit() == null)
                    throw new IllegalArgumentException("Successful catalog outcome and audit are required");
                audit(request, outcome.audit());
                return complete(current, owner, outcome.status(), serialize(ApiResult.ok(outcome.data(), request.traceId())));
            });
        } catch (ApiException error) {
            // The failed business transaction has rolled back. Persist only deterministic business rejection.
            // A failure to determine commit outcome is never treated as permission to rerun the operation.
            if (!Set.of(400, 404, 409, 422).contains(error.status()) || error.code().equals("REQUEST_IN_PROGRESS")) throw error;
            return transaction.execute(status -> {
                Entry current = read(scope, true);
                if (current.state().equals("COMPLETED")) return replay(current);
                ensureOwner(current, owner);
                return complete(current, owner, error.status(), serialize(new ErrorBody(error.code(), error.getMessage(), request.traceId(), null)));
            });
        }
        // Infrastructure/unknown failures retain IN_PROGRESS. Recovery takes its row lock after lease expiry.
    }

    private Entry claim(Scope scope, String owner) {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            Entry found;
            try { found = read(scope, false); }
            catch (EmptyResultDataAccessException absent) { found = null; }
            // Read committed claims first: a duplicate INSERT would otherwise wait on a live work row lock.
            if (found != null) return claimExisting(scope, found, owner, now);
            try {
                jdbc.update("INSERT INTO idempotency_request(actor_scope,method,route,key_hash,request_hash,owner_token,state,lease_until,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,'IN_PROGRESS',?,?,?,?)",
                    scope.actor(), scope.method(), scope.route(), scope.keyHash(), scope.requestHash(), owner,
                    Timestamp.from(now.plusSeconds(120)), Timestamp.from(now.plusSeconds(86400)), Timestamp.from(now), Timestamp.from(now));
                return read(scope, false);
            } catch (DuplicateKeyException duplicate) {
                return claimExisting(scope, read(scope, false), owner, now);
            }
        });
    }

    private Entry claimExisting(Scope scope, Entry entry, String owner, Instant now) {
        boolean expired = entry.state().equals("COMPLETED") ? !entry.expiresAt().isAfter(now) : !entry.leaseUntil().isAfter(now);
        if (!expired) return existing(scope, entry);
        entry = read(scope, true);
        if (entry.state().equals("COMPLETED") && entry.expiresAt().isAfter(now)) return existing(scope, entry);
        if (entry.state().equals("IN_PROGRESS")) {
            if (!entry.requestHash().equals(scope.requestHash())) throw conflict();
            if (entry.leaseUntil().isAfter(now)) throw inProgress();
        }
        jdbc.update("UPDATE idempotency_request SET request_hash=?,owner_token=?,state='IN_PROGRESS',http_status=NULL,encrypted_response=NULL,lease_until=?,expires_at=?,updated_at=? WHERE id=CAST(? AS UNSIGNED)",
            scope.requestHash(), owner, Timestamp.from(now.plusSeconds(120)), Timestamp.from(now.plusSeconds(86400)), Timestamp.from(now), entry.id());
        return read(scope, false);
    }

    private Entry existing(Scope scope, Entry entry) {
        if (!entry.requestHash().equals(scope.requestHash())) throw conflict();
        if (entry.state().equals("IN_PROGRESS")) throw inProgress();
        return entry;
    }
    private Entry read(Scope scope, boolean lock) {
        try {
            return jdbc.queryForObject(SELECT + WHERE + (lock ? " FOR UPDATE NOWAIT" : ""), ENTRY,
                scope.actor(), scope.method(), scope.route(), scope.keyHash());
        } catch (CannotAcquireLockException busy) {
            if (lock) throw inProgress();
            throw busy;
        } catch (UncategorizedSQLException error) {
            // JDBC's default subclass translator cannot classify MySQL NOWAIT (HY000).
            // Use Spring's shipped MySQL vendor-code mapping, only for this lock acquisition.
            if (lock && mysqlErrors.translate("Acquire idempotency row lock", null, error.getSQLException()) instanceof CannotAcquireLockException)
                throw inProgress();
            throw error;
        }
    }
    private void ensureOwner(Entry entry, String owner) {
        if (!entry.state().equals("IN_PROGRESS") || !entry.owner().equals(owner)) throw inProgress();
    }
    private Response complete(Entry entry, String owner, int status, String body) {
        byte[] encrypted = cipher.encrypt(body, entry.id() + ":" + entry.owner() + ":" + entry.requestHash());
        int updated = jdbc.update("UPDATE idempotency_request SET state='COMPLETED',http_status=?,encrypted_response=?,updated_at=?,expires_at=? WHERE id=CAST(? AS UNSIGNED) AND owner_token=? AND state='IN_PROGRESS'",
            status, encrypted, Timestamp.from(clock.instant()), Timestamp.from(clock.instant().plusSeconds(86400)), entry.id(), owner);
        if (updated != 1) throw new IllegalStateException("Lost idempotency ownership");
        return new Response(status, body, false);
    }
    private Response replay(Entry entry) {
        return new Response(entry.status(), cipher.decrypt(entry.response(), entry.id() + ":" + entry.owner() + ":" + entry.requestHash()), true);
    }
    private void audit(Request request, Audit audit) {
        if ("PAYMENT".equals(audit.resourceType())) {
            if (audit.resourceId() == null || !audit.resourceId().matches("[1-9][0-9]{0,18}") || !"POST".equals(request.method()))
                throw new IllegalArgumentException("Validated payment audit identity required");
            boolean allowed = actorType == AccountType.CUSTOMER && MockPaymentApplication.START_ROUTE.equals(request.route())
                && "PAYMENT_CREATE".equals(audit.action());
            allowed = allowed || (actorType == AccountType.STAFF && MockPaymentApplication.successRoute(audit.resourceId()).equals(request.route())
                && "PAYMENT_MOCK_SUCCESS".equals(audit.action()));
            if (!allowed) throw new IllegalArgumentException("Invalid payment audit route or actor");
            writeAudit(request, audit, Set.of("id", "state"));
            return;
        }
        Set<String> resources=actorType==AccountType.CUSTOMER?Set.of("ADDRESS","CART","QUOTE","ORDER"):Set.of("BRAND","CATEGORY","PRODUCT","SKU","ASSET");
        Set<String> actions=actorType==AccountType.CUSTOMER?(audit.resourceType().equals("QUOTE")?Set.of("QUOTE_CREATE"):
            audit.resourceType().equals("ORDER")?Set.of("ORDER_CREATE","ORDER_CANCEL"):Set.of(audit.resourceType()+"_CREATE",audit.resourceType()+"_UPDATE",audit.resourceType()+"_DELETE")):
            Set.of(audit.resourceType()+"_CREATE",audit.resourceType()+"_UPDATE",audit.resourceType()+"_STATE");
        if (!resources.contains(audit.resourceType()) || !actions.contains(audit.action())
            || audit.resourceId() == null || !audit.resourceId().matches("[1-9][0-9]{0,18}"))
            throw new IllegalArgumentException("Validated catalog audit identity required");
        Set<String> fields=actorType==AccountType.CUSTOMER?(audit.resourceType().equals("QUOTE")?Set.of("id"):
            audit.resourceType().equals("ORDER")?Set.of("id","state"):CUSTOMER_SNAPSHOT_FIELDS):SNAPSHOT_FIELDS;
        writeAudit(request, audit, fields);
    }

    private void writeAudit(Request request, Audit audit, Set<String> fields) {
        ObjectNode masked = json.createObjectNode(); masked.set("before", snapshot(audit.before(),fields)); masked.set("after", snapshot(audit.after(),fields));
        jdbc.update("INSERT INTO audit_log(actor_id,action,resource_type,resource_id,masked_before_after,result,trace_id) VALUES(CAST(? AS UNSIGNED),?,?,CAST(? AS UNSIGNED),?,'SUCCESS',?)",
            request.actorId(), audit.action(), audit.resourceType(), audit.resourceId(), serialize(masked), request.traceId());
    }
    private JsonNode snapshot(JsonNode source,Set<String> fields) {
        if (source == null || source.isNull()) return json.nullNode();
        if (!source.isObject()) throw new IllegalArgumentException("Audit snapshot must be a catalog object");
        ObjectNode result = json.createObjectNode();
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (value != null && value.isValueNode()) result.set(field, value);
        }
        return result;
    }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Catalog write serialization failed", error); }
    }
    private static ApiException conflict() { return new ApiException(409, "IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同请求"); }
    private static ApiException inProgress() { return new ApiException(409, "REQUEST_IN_PROGRESS", "请求仍在处理中，请稍后使用原幂等键重试"); }
}
