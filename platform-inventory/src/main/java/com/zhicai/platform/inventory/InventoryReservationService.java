package com.zhicai.platform.inventory;

import com.zhicai.platform.common.ApiException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal ORDER core, explicitly registered by a future order application as a transactional proxy.
 * Every write joins the caller's transaction; callers must allow failures to roll it back.
 * The outer transaction must explicitly use READ_COMMITTED and be writable. Default isolation,
 * REPEATABLE_READ and other levels are rejected before mapper access: locking missing reservation
 * rows under REPEATABLE_READ can introduce gap-lock deadlocks between first holds on different SKUs.
 * Direct construction alone does not install Spring's transaction interceptor.
 */
public class InventoryReservationService {
    public record Command(String businessId, String warehouseId, List<Line> lines, String actorId, String traceId) {
        public Command { if (lines != null) lines = Collections.unmodifiableList(new ArrayList<>(lines)); }
    }
    public record Line(String businessLineId, String skuId, int quantity) {}
    private final StockMapper mapper;
    public InventoryReservationService(StockMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockMapper.Reservation> hold(Command command) {
        var rows = locked(command, true);
        // Finish every reference and availability check before changing even the first line.
        for (var row : rows) {
            if (row.reservation() != null) {
                proveReference(command, row);
                if (!"HELD".equals(row.reservation().state())) throw conflict();
            } else if (row.balance() == null || row.balance().onHand() - row.balance().reserved() < row.line().quantity()) {
                throw new ApiException(409, "STOCK_INSUFFICIENT", "库存不足，请调整商品或稍后重试");
            }
        }
        var result = new ArrayList<StockMapper.Reservation>();
        for (var row : rows) {
            if (row.reservation() != null) { result.add(row.reservation()); continue; }
            var line = row.line(); var balance = row.balance();
            if (mapper.increaseReserved(balance.id(), line.quantity(), balance.version()) != 1) throw conflict();
            var draft = new StockMapper.NewReservation(line.businessLineId(), command.warehouseId(), line.skuId(), line.quantity());
            if (mapper.insertReservation(draft) != 1 || draft.id == null) throw new IllegalStateException("Stock reservation identity not inserted");
            ledger(command, row, "RESERVE", line.quantity());
            result.add(new StockMapper.Reservation(draft.id, line.businessLineId(), command.warehouseId(), line.skuId(), line.quantity(), "HELD", 0));
        }
        return List.copyOf(result);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockMapper.Reservation> release(Command command) {
        var rows = locked(command, false);
        for (var row : rows) {
            if (row.balance() == null || row.reservation() == null) throw conflict();
            proveReference(command, row);
            var state = row.reservation().state();
            if (!"HELD".equals(state) && !"RELEASED".equals(state)) throw conflict();
            if ("HELD".equals(state) && row.balance().reserved() < row.line().quantity()) throw conflict();
        }
        var result = new ArrayList<StockMapper.Reservation>();
        for (var row : rows) {
            var reservation = row.reservation();
            if ("RELEASED".equals(reservation.state())) { result.add(reservation); continue; }
            if (mapper.decreaseReserved(row.balance().id(), row.line().quantity(), row.balance().version()) != 1) throw conflict();
            if (mapper.markReleased(reservation.id(), reservation.version()) != 1) throw conflict();
            ledger(command, row, "RELEASE", -row.line().quantity());
            result.add(new StockMapper.Reservation(reservation.id(), reservation.businessLineId(), reservation.warehouseId(),
                reservation.skuId(), reservation.quantity(), "RELEASED", Math.addExact(reservation.version(), 1)));
        }
        return List.copyOf(result);
    }

    private record Locked(Line line, StockMapper.Balance balance, StockMapper.Reservation reservation) {}

    private List<Locked> locked(Command command, boolean hold) {
        validate(command);
        requireTransaction();
        var warehouse = mapper.lockWarehouse(command.warehouseId());
        if (warehouse == null || (hold && (!warehouse.enabled() || !warehouse.fulfillmentEnabled()))) throw conflict();
        var lines = command.lines().stream().sorted(Comparator.comparing(line -> new BigInteger(line.skuId()))).toList();
        var balances = new ArrayList<StockMapper.Balance>();
        // Both operations use this same lock order, including when some balance is absent.
        for (var line : lines) balances.add(mapper.lockBalance(command.warehouseId(), line.skuId()));
        var rows = new ArrayList<Locked>();
        for (int i = 0; i < lines.size(); i++) rows.add(new Locked(lines.get(i), balances.get(i), mapper.lockReservation(lines.get(i).businessLineId())));
        for (var row : rows) {
            var balance = row.balance();
            if (balance != null && (!command.warehouseId().equals(balance.warehouseId()) || !row.line().skuId().equals(balance.skuId())
                || balance.onHand() < balance.reserved() || balance.reserved() < 0 || balance.quarantine() < 0 || balance.version() < 0)) {
                throw new IllegalStateException("Invalid locked stock balance");
            }
        }
        return rows;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), TransactionDefinition.ISOLATION_READ_COMMITTED)
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException("Stock reservation requires an active, writable, explicitly READ_COMMITTED transaction");
        }
    }

    private void proveReference(Command command, Locked row) {
        var line = row.line(); var reservation = row.reservation();
        if (!line.businessLineId().equals(reservation.businessLineId()) || !command.warehouseId().equals(reservation.warehouseId())
            || !line.skuId().equals(reservation.skuId()) || line.quantity() != reservation.quantity()) throw conflict();
        // Reservation has no biz_id: its immutable RESERVE ledger must establish the original order.
        var evidence = mapper.reserveEvidence(line.businessLineId());
        if (evidence == null || evidence.size() != 1) throw conflict();
        var original = evidence.getFirst();
        if (!command.businessId().equals(original.businessId()) || !line.businessLineId().equals(original.businessLineId())
            || !command.warehouseId().equals(original.warehouseId()) || !line.skuId().equals(original.skuId())
            || line.quantity() != original.quantity()) throw conflict();
    }

    private void ledger(Command command, Locked row, String action, int delta) {
        var balance = row.balance(); var line = row.line();
        var ledger = new StockMapper.Ledger(command.businessId(), line.businessLineId(), command.warehouseId(), line.skuId(), action,
            balance.onHand(), balance.reserved(), balance.quarantine(), delta, command.actorId(), command.traceId());
        if (mapper.insertLedger(ledger) != 1) throw new IllegalStateException("Stock ledger not inserted");
    }

    private static void validate(Command command) {
        if (command == null || !validId(command.businessId()) || !validId(command.warehouseId())
            || (command.actorId() != null && !validId(command.actorId())) || command.traceId() == null
            || !command.traceId().matches("[A-Za-z0-9._:-]{1,64}") || command.lines() == null
            || command.lines().isEmpty() || command.lines().size() > 100) bad();
        var skuIds = new HashSet<String>(); var lineIds = new HashSet<String>();
        for (var line : command.lines()) {
            if (line == null || !validId(line.businessLineId()) || !validId(line.skuId()) || line.quantity() < 1 || line.quantity() > 99
                || !skuIds.add(line.skuId()) || !lineIds.add(line.businessLineId())) bad();
        }
    }
    private static boolean validId(String value) { return value != null && value.matches("[1-9][0-9]{0,18}"); }
    private static void bad() { throw new ApiException(400, "VALIDATION_ERROR", "库存预留或释放请求字段不合法"); }
    private static ApiException conflict() { return new ApiException(409, "STOCK_RESERVATION_CONFLICT", "库存预留状态或业务引用已冲突"); }
}
