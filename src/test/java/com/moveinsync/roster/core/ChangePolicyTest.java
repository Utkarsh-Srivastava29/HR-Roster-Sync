package com.moveinsync.roster.core;

import com.moveinsync.roster.core.domain.ChangeType;
import com.moveinsync.roster.core.domain.Employee;
import com.moveinsync.roster.core.domain.EmployeeField;
import com.moveinsync.roster.core.domain.EmployeeStatus;
import com.moveinsync.roster.core.domain.HrEvent;
import com.moveinsync.roster.core.domain.HrProfile;
import com.moveinsync.roster.core.domain.TransportEdit;
import com.moveinsync.roster.core.ingest.ChangePolicy;
import com.moveinsync.roster.core.ingest.Decision;
import com.moveinsync.roster.core.ingest.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangePolicyTest {

    private final ChangePolicy p = new ChangePolicy();
    private final Instant t = Instant.parse("2026-10-30T10:00:00Z");

    private HrProfile prof(String office, String shift) {
        return new HrProfile("Asha Rao", "asha@acme.example", "+919800000001", null, office, shift);
    }

    private HrEvent ev(HrEvent.Type type, long v, String office, String shift) {
        return new HrEvent("acme", type, "E1", v, t, type == HrEvent.Type.DEACTIVATE ? null : prof(office, shift), Set.of());
    }

    private Employee at(long v, String office, String shift) {
        return p.decideHr(Optional.empty(), ev(HrEvent.Type.CREATE, v, office, shift), t).next();
    }

    @Test
    void createThenStaleThenDuplicateThenConflict() {
        Employee e = at(5, "BLR1", "GEN");
        assertEquals(EmployeeStatus.ACTIVE, e.status());
        assertEquals(Outcome.STALE, p.decideHr(Optional.of(e), ev(HrEvent.Type.UPDATE, 4, "HYD1", "GEN"), t).outcome());
        assertEquals(Outcome.DUPLICATE, p.decideHr(Optional.of(e), ev(HrEvent.Type.UPDATE, 5, "BLR1", "GEN"), t).outcome());
        Decision c = p.decideHr(Optional.of(e), ev(HrEvent.Type.UPDATE, 5, "HYD1", "GEN"), t);
        assertEquals(Outcome.CONFLICT, c.outcome());
        assertNull(c.next());
    }

    @Test
    void newerVersionChangesOnlyTheFieldsThatDiffer() {
        Employee e = at(1, "BLR1", "GEN");
        Decision d = p.decideHr(Optional.of(e), ev(HrEvent.Type.UPDATE, 2, "HYD1", "GEN"), t);
        assertEquals(Outcome.APPLIED, d.outcome());
        assertEquals(Set.of(EmployeeField.OFFICE_CODE), d.changed());
        assertEquals(ChangeType.UPDATED, d.changeType());
        Decision same = p.decideHr(Optional.of(d.next()), ev(HrEvent.Type.UPDATE, 3, "HYD1", "GEN"), t);
        assertEquals(Outcome.UNCHANGED, same.outcome());
        assertEquals(3, same.next().hrVersion(), "version still recorded");
    }

    @Test
    void hrUpdateNeverTouchesTransportFields() {
        Employee e = at(1, "BLR1", "GEN");
        Employee withPickup = p.decideTransport(e, new TransportEdit("Gate 4", List.of("WHEELCHAIR"), 0), t).next();
        Employee after = p.decideHr(Optional.of(withPickup), ev(HrEvent.Type.UPDATE, 2, "HYD1", "EVE"), t).next();
        assertEquals("Gate 4", after.transport().pickupPoint());
        assertEquals(List.of("WHEELCHAIR"), after.transport().specialAssistance());
        assertEquals(1, after.transportVersion());
    }

    @Test
    void transportEditsUseOptimisticConcurrencyAndNeverTouchHr() {
        Employee e = at(3, "BLR1", "GEN");
        Decision ok = p.decideTransport(e, new TransportEdit("Gate 4", List.of(), 0), t);
        assertEquals(Outcome.APPLIED, ok.outcome());
        assertEquals(3, ok.next().hrVersion());
        assertEquals(Set.of(EmployeeField.PICKUP_POINT), ok.changed());
        assertEquals(Outcome.CONFLICT, p.decideTransport(ok.next(), new TransportEdit("Gate 9", List.of(), 0), t).outcome());
    }

    @Test
    void deactivateAndReactivate() {
        Employee e = at(1, "BLR1", "GEN");
        Decision d = p.decideHr(Optional.of(e), ev(HrEvent.Type.DEACTIVATE, 2, null, null), t);
        assertEquals(ChangeType.DEACTIVATED, d.changeType());
        assertEquals(Outcome.DUPLICATE, p.decideHr(Optional.of(d.next()), ev(HrEvent.Type.DEACTIVATE, 2, null, null), t).outcome());
        Decision back = p.decideHr(Optional.of(d.next()), ev(HrEvent.Type.UPDATE, 3, "BLR1", "GEN"), t);
        assertEquals(ChangeType.REACTIVATED, back.changeType());
        assertEquals(Set.of(EmployeeField.STATUS), back.changed());
    }

    @Test
    void deactivateBeforeCreateLeavesATombstoneSoTheLateCreateIsStale() {
        Decision tomb = p.decideHr(Optional.empty(), ev(HrEvent.Type.DEACTIVATE, 2, null, null), t);
        assertEquals(EmployeeStatus.INACTIVE, tomb.next().status());
        assertTrue(tomb.next().hr().unknown());
        assertEquals(Outcome.STALE, p.decideHr(Optional.of(tomb.next()), ev(HrEvent.Type.CREATE, 1, "BLR1", "GEN"), t).outcome());
        Decision rehire = p.decideHr(Optional.of(tomb.next()), ev(HrEvent.Type.CREATE, 3, "BLR1", "GEN"), t);
        assertEquals(ChangeType.CREATED, rehire.changeType());
    }

    @Test
    void reconciliationDeactivationIsGuardedAgainstNewerChanges() {
        Employee e = at(4, "BLR1", "GEN");
        Instant exportTaken = t.plusSeconds(60);
        assertEquals(Outcome.APPLIED, p.decideReconciliationDeactivate(Optional.of(e), 4, exportTaken, t).outcome());
        assertEquals(Outcome.STALE, p.decideReconciliationDeactivate(Optional.of(e), 3, exportTaken, t).outcome());
        assertEquals(Outcome.STALE, p.decideReconciliationDeactivate(Optional.of(e), 4, t.minusSeconds(60), t).outcome(),
                "changed after the export was taken");
    }
}
