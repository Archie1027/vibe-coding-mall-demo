package com.zhicai.platform.inventory;

import com.zhicai.platform.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.zhicai.platform.inventory.InventoryReservationService.*;
import static com.zhicai.platform.inventory.StockMapper.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InventoryReservationServiceTest {
    private StockMapper mapper;
    private InventoryReservationService service;
    private final Command one = command(new Line("31", "2", 2));
    @BeforeEach void setup() {
        mapper = mock(StockMapper.class); service = new InventoryReservationService(mapper);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
    @AfterEach void clearTransactionContext() { TransactionSynchronizationManager.clear(); }
    private Command command(Line... lines) { return new Command("21", "1", List.of(lines), "9", "stock-test_1"); }
    private Balance balance(String sku, int onHand, int reserved) { return new Balance("10" + sku, "1", sku, onHand, reserved, 3, 7); }
    private Reservation reservation(String state) { return new Reservation("41", "31", "1", "2", 2, state, 4); }
    private void warehouse(boolean enabled) { when(mapper.lockWarehouse("1")).thenReturn(new Warehouse("1", enabled, enabled)); }
    private void existing(String state) {
        warehouse(true); when(mapper.lockBalance("1", "2")).thenReturn(balance("2", 10, 2));
        when(mapper.lockReservation("31")).thenReturn(reservation(state));
        when(mapper.reserveEvidence("31")).thenReturn(List.of(new ReserveEvidence("21", "31", "1", "2", 2)));
    }
    private void holdWrites() {
        when(mapper.increaseReserved(anyString(), anyInt(), anyLong())).thenReturn(1);
        doAnswer(call -> { ((NewReservation) call.getArgument(0)).id = "9007199254740993"; return 1; }).when(mapper).insertReservation(any());
        when(mapper.insertLedger(any())).thenReturn(1);
    }
    private void noWrites() {
        verify(mapper, never()).increaseReserved(anyString(), anyInt(), anyLong());
        verify(mapper, never()).decreaseReserved(anyString(), anyInt(), anyLong());
        verify(mapper, never()).insertReservation(any()); verify(mapper, never()).markReleased(anyString(), anyLong());
        verify(mapper, never()).insertLedger(any());
    }
    private void conflict(Runnable action) { var error = assertThrows(ApiException.class, action::run); assertEquals(409, error.status()); }

    private void unsupportedTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> service.hold(one));
        assertThrows(IllegalTransactionStateException.class, () -> service.release(one));
        verifyNoInteractions(mapper);
    }
    @Test void missingTransactionIsRejectedBeforeMapperIoEvenWithoutProxy() {
        TransactionSynchronizationManager.setActualTransactionActive(false); unsupportedTransaction();
    }
    @Test void unspecifiedIsolationIsRejectedBeforeMapperIo() {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null); unsupportedTransaction();
    }
    @Test void unsupportedExplicitIsolationIsRejectedBeforeMapperIo() {
        for (int isolation : List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_SERIALIZABLE,
            TransactionDefinition.ISOLATION_READ_UNCOMMITTED)) {
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation); unsupportedTransaction();
        }
    }
    @Test void readOnlyReadCommittedTransactionIsRejectedBeforeMapperIo() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true); unsupportedTransaction();
    }

    @Test void commandCopiesItsLines() {
        var input = new ArrayList<>(one.lines()); var copy = new Command("21", "1", input, null, "system");
        input.clear(); assertEquals(1, copy.lines().size()); assertThrows(UnsupportedOperationException.class, () -> copy.lines().clear());
    }
    @Test void invalidCommandsFailBeforeAnyIo() {
        var bad = new ArrayList<Command>(); bad.add(null);
        bad.add(new Command("0", "1", one.lines(), null, "trace")); bad.add(new Command("01", "1", one.lines(), null, "trace"));
        bad.add(new Command("21", "10000000000000000000", one.lines(), null, "trace")); bad.add(new Command("21", "1", one.lines(), "0", "trace"));
        for (String trace : List.of("汉字", "bad\ntrace", "a".repeat(65), "")) bad.add(new Command("21", "1", one.lines(), null, trace));
        bad.add(new Command("21", "1", null, null, "trace")); bad.add(command());
        bad.add(command(new Line("31", "2", 0))); bad.add(command(new Line("31", "2", 100)));
        bad.add(command(new Line("31", "2", 1), new Line("32", "2", 1))); bad.add(command(new Line("31", "2", 1), new Line("31", "3", 1)));
        bad.add(new Command("21", "1", java.util.Arrays.asList((Line) null), null, "trace"));
        bad.add(command(new Line("0", "2", 1))); bad.add(command(new Line("31", "-2", 1)));
        var tooMany = new ArrayList<Line>(); for (int i = 1; i <= 101; i++) tooMany.add(new Line("" + i, "" + i, 1));
        bad.add(new Command("21", "1", tooMany, null, "trace"));
        for (var input : bad) {
            assertEquals("VALIDATION_ERROR", assertThrows(ApiException.class, () -> service.hold(input)).code());
            assertEquals("VALIDATION_ERROR", assertThrows(ApiException.class, () -> service.release(input)).code());
        }
        verifyNoInteractions(mapper);
    }
    @Test void holdLocksAllBalancesThenReservationsInNumericSkuOrder() {
        warehouse(true); holdWrites();
        for (String sku : List.of("2", "10", "9007199254740993", "9999999999999999999")) when(mapper.lockBalance("1", sku)).thenReturn(balance(sku, 10, 0));
        var input = command(new Line("34", "9999999999999999999", 1), new Line("33", "9007199254740993", 1), new Line("32", "10", 1), new Line("31", "2", 2));
        var result = service.hold(input); assertEquals(4, result.size()); assertEquals("2", result.getFirst().skuId()); assertEquals("9007199254740993", result.getFirst().id());
        var order = inOrder(mapper); order.verify(mapper).lockWarehouse("1");
        for (String sku : List.of("2", "10", "9007199254740993", "9999999999999999999")) order.verify(mapper).lockBalance("1", sku);
        for (String line : List.of("31", "32", "33", "34")) order.verify(mapper).lockReservation(line);
        order.verify(mapper).increaseReserved("102", 2, 7);
        verify(mapper).insertLedger(new Ledger("21", "31", "1", "2", "RESERVE", 10, 0, 3, 2, "9", "stock-test_1"));
    }
    @Test void holdRejectsMissingDisabledOrNonFulfillmentWarehouse() {
        conflict(() -> service.hold(one)); noWrites();
        for (var row : List.of(new Warehouse("1", false, true), new Warehouse("1", true, false))) {
            when(mapper.lockWarehouse("1")).thenReturn(row); conflict(() -> service.hold(one));
        }
        verify(mapper, never()).lockBalance(anyString(), anyString());
    }
    @Test void laterInsufficientOrMissingBalanceCausesNoEarlierMutation() {
        warehouse(true); when(mapper.lockBalance("1", "2")).thenReturn(balance("2", 10, 0));
        var input = command(new Line("31", "2", 2), new Line("32", "3", 2));
        assertEquals("STOCK_INSUFFICIENT", assertThrows(ApiException.class, () -> service.hold(input)).code()); noWrites();
        when(mapper.lockBalance("1", "3")).thenReturn(balance("3", 2, 1));
        assertEquals("STOCK_INSUFFICIENT", assertThrows(ApiException.class, () -> service.hold(input)).code()); noWrites();
    }
    @Test void exactHeldRetryDoesNotRequireRemainingAvailabilityOrWriteAgain() {
        existing("HELD"); when(mapper.lockBalance("1", "2")).thenReturn(balance("2", 2, 2));
        assertEquals(List.of(reservation("HELD")), service.hold(one)); noWrites();
    }
    @Test void holdRetryRequiresOriginalOrderAndMatchingReference() {
        existing("HELD");
        var mismatches = List.of(new ReserveEvidence("22", "31", "1", "2", 2), new ReserveEvidence("21", "31", "9", "2", 2),
            new ReserveEvidence("21", "31", "1", "3", 2), new ReserveEvidence("21", "31", "1", "2", 3));
        for (var evidence : mismatches) { when(mapper.reserveEvidence("31")).thenReturn(List.of(evidence)); conflict(() -> service.hold(one)); }
        when(mapper.reserveEvidence("31")).thenReturn(List.of()); conflict(() -> service.hold(one));
        when(mapper.reserveEvidence("31")).thenReturn(List.of(mismatches.getFirst(), mismatches.getFirst())); conflict(() -> service.hold(one)); noWrites();
    }
    @Test void changedReservationQuantitySkuOrWarehouseConflicts() {
        existing("HELD");
        for (var row : List.of(new Reservation("41", "31", "9", "2", 2, "HELD", 4), new Reservation("41", "31", "1", "3", 2, "HELD", 4), new Reservation("41", "31", "1", "2", 3, "HELD", 4))) {
            when(mapper.lockReservation("31")).thenReturn(row); conflict(() -> service.hold(one)); conflict(() -> service.release(one));
        }
        noWrites();
    }
    @Test void holdCannotReviveReleasedOrConsumedReservation() {
        existing("RELEASED"); conflict(() -> service.hold(one)); existing("CONSUMED"); conflict(() -> service.hold(one)); noWrites();
    }
    @Test void holdPrevalidatesLaterReferenceBeforeAnyMutation() {
        existing("RELEASED"); when(mapper.lockBalance("1", "1")).thenReturn(balance("1", 10, 0));
        conflict(() -> service.hold(command(new Line("30", "1", 2), new Line("31", "2", 2)))); noWrites();
    }
    @Test void holdConditionalRowcountFailureStopsImmediately() {
        warehouse(true); when(mapper.lockBalance("1", "2")).thenReturn(balance("2", 10, 0));
        conflict(() -> service.hold(one)); verify(mapper, never()).insertReservation(any()); verify(mapper, never()).insertLedger(any());
    }
    @Test void holdReservationOrLedgerWriteFailurePropagates() {
        warehouse(true); when(mapper.lockBalance("1", "2")).thenReturn(balance("2", 10, 0)); holdWrites();
        doReturn(0).when(mapper).insertReservation(any()); assertThrows(RuntimeException.class, () -> service.hold(one)); verify(mapper, never()).insertLedger(any());
        holdWrites(); when(mapper.insertLedger(any())).thenReturn(0); assertThrows(RuntimeException.class, () -> service.hold(one));
        when(mapper.insertLedger(any())).thenThrow(new IllegalStateException("injected")); assertEquals("injected", assertThrows(IllegalStateException.class, () -> service.hold(one)).getMessage());
    }
    @Test void releasePermitsDisabledWarehouseAndWritesExactInverseLedger() {
        existing("HELD"); warehouse(false); when(mapper.decreaseReserved("102", 2, 7)).thenReturn(1); when(mapper.markReleased("41", 4)).thenReturn(1); when(mapper.insertLedger(any())).thenReturn(1);
        assertEquals(List.of(new Reservation("41", "31", "1", "2", 2, "RELEASED", 5)), service.release(one));
        var order = inOrder(mapper); order.verify(mapper).lockWarehouse("1"); order.verify(mapper).lockBalance("1", "2"); order.verify(mapper).lockReservation("31");
        order.verify(mapper).reserveEvidence("31"); order.verify(mapper).decreaseReserved("102", 2, 7); order.verify(mapper).markReleased("41", 4);
        order.verify(mapper).insertLedger(new Ledger("21", "31", "1", "2", "RELEASE", 10, 2, 3, -2, "9", "stock-test_1"));
    }
    @Test void repeatedReleaseDoesNotMutateButStillProvesOrder() {
        existing("RELEASED"); assertEquals(List.of(reservation("RELEASED")), service.release(one)); noWrites();
        when(mapper.reserveEvidence("31")).thenReturn(List.of(new ReserveEvidence("99", "31", "1", "2", 2))); conflict(() -> service.release(one)); noWrites();
    }
    @Test void releaseMissingOrConsumedConflictsWithoutMutation() {
        warehouse(true); when(mapper.lockBalance("1", "2")).thenReturn(balance("2", 10, 2)); conflict(() -> service.release(one));
        existing("CONSUMED"); conflict(() -> service.release(one)); noWrites();
    }
    @Test void releasePrevalidatesAllRowsAndUsesSameNumericLockOrder() {
        existing("HELD"); when(mapper.lockBalance("1", "10")).thenReturn(balance("10", 10, 2));
        conflict(() -> service.release(command(new Line("32", "10", 2), new Line("31", "2", 2)))); noWrites();
        var order = inOrder(mapper); order.verify(mapper).lockWarehouse("1"); order.verify(mapper).lockBalance("1", "2"); order.verify(mapper).lockBalance("1", "10");
        order.verify(mapper).lockReservation("31"); order.verify(mapper).lockReservation("32");
    }
    @Test void releaseRejectsCorruptReservedBeforeAnyMutation() {
        existing("HELD"); when(mapper.lockBalance("1", "2")).thenReturn(balance("2", 10, 1)); conflict(() -> service.release(one)); noWrites();
    }
    @Test void releaseConditionalOrLedgerFailureIsNeverSwallowed() {
        existing("HELD"); conflict(() -> service.release(one)); verify(mapper, never()).markReleased(anyString(), anyLong());
        when(mapper.decreaseReserved("102", 2, 7)).thenReturn(1); conflict(() -> service.release(one)); verify(mapper, never()).insertLedger(any());
        when(mapper.markReleased("41", 4)).thenReturn(1); assertThrows(RuntimeException.class, () -> service.release(one));
    }
    @Test void nullActorIsAllowedForSystemLedger() {
        warehouse(true); when(mapper.lockBalance("1", "2")).thenReturn(balance("2", 10, 0)); holdWrites(); service.hold(new Command("21", "1", one.lines(), null, "system"));
        verify(mapper).insertLedger(argThat(row -> row.actorId() == null));
    }
    @Test void publicMethodsRequireExistingTransactionAndNoRuntimeServiceRegistration() throws Exception {
        for (String method : List.of("hold", "release")) {
            var transaction = InventoryReservationService.class.getMethod(method, Command.class).getAnnotation(Transactional.class);
            assertNotNull(transaction); assertEquals(Propagation.MANDATORY, transaction.propagation());
        }
        assertNull(InventoryReservationService.class.getAnnotation(Service.class));
    }
    @Test void mapperXmlParsesEveryOperationWithoutDatabaseAccess() throws Exception {
        var configuration = new Configuration();
        try (var stream = getClass().getResourceAsStream("/mapper/StockMapper.xml")) {
            assertNotNull(stream); new XMLMapperBuilder(stream, configuration, "mapper/StockMapper.xml", configuration.getSqlFragments()).parse();
        }
        for (var method : StockMapper.class.getDeclaredMethods()) assertTrue(configuration.hasStatement(StockMapper.class.getName() + "." + method.getName()));
    }
    @Test void mapperBindsAllStatementsIncludingNullableActorWithoutDatabaseAccess() throws Exception {
        var configuration = new Configuration();
        try (var stream = getClass().getResourceAsStream("/mapper/StockMapper.xml")) {
            new XMLMapperBuilder(stream, configuration, "mapper/StockMapper.xml", configuration.getSqlFragments()).parse();
        }
        var named = new MapperMethod.ParamMap<Object>();
        named.put("id", "9007199254740993"); named.put("warehouseId", "1"); named.put("skuId", "2");
        named.put("businessLineId", "31"); named.put("quantity", 2); named.put("version", 7L);
        for (String name : List.of("lockWarehouse", "lockBalance", "lockReservation", "reserveEvidence", "increaseReserved", "decreaseReserved", "markReleased")) {
            var statement = configuration.getMappedStatement(StockMapper.class.getName() + "." + name);
            new DefaultParameterHandler(statement, named, statement.getBoundSql(named)).setParameters(mock(java.sql.PreparedStatement.class));
        }
        var draft = new NewReservation("31", "1", "2", 2);
        var reservation = configuration.getMappedStatement(StockMapper.class.getName() + ".insertReservation");
        new DefaultParameterHandler(reservation, draft, reservation.getBoundSql(draft)).setParameters(mock(java.sql.PreparedStatement.class));
        var ledger = new Ledger("21", "31", "1", "2", "RESERVE", 10, 0, 3, 2, null, "system");
        var insert = configuration.getMappedStatement(StockMapper.class.getName() + ".insertLedger");
        var prepared = mock(java.sql.PreparedStatement.class);
        var bound = insert.getBoundSql(ledger);
        new DefaultParameterHandler(insert, ledger, bound).setParameters(prepared);
        int actorIndex = java.util.stream.IntStream.range(0, bound.getParameterMappings().size())
            .filter(i -> bound.getParameterMappings().get(i).getProperty().equals("actorId")).findFirst().orElseThrow() + 1;
        verify(prepared).setNull(actorIndex, java.sql.Types.VARCHAR);
    }
}
