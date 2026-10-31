package com.moveinsync.roster.core;

import com.moveinsync.roster.core.domain.EmployeeField;
import com.moveinsync.roster.core.domain.HrEvent;
import com.moveinsync.roster.core.validation.ErrorCode;
import com.moveinsync.roster.core.validation.FieldError;
import com.moveinsync.roster.core.validation.RawHrRecord;
import com.moveinsync.roster.core.validation.RecordValidator;
import com.moveinsync.roster.core.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationTest {

    private final RecordValidator v = new RecordValidator();
    private final Instant now = Instant.parse("2026-10-31T00:00:00Z");

    private ValidationResult check(RawHrRecord r) {
        return v.validate("acme", r, Raw.CATALOG, now);
    }

    @Test
    void validRecordIsNormalised() {
        ValidationResult r = check(Raw.create("E1", 1));
        assertTrue(r.valid(), r.errors().toString());
        HrEvent e = r.event();
        assertEquals("asha.rao@acme.example", e.profile().email());
        assertEquals("+919800000001", e.profile().phone());
        assertEquals(HrEvent.Type.CREATE, e.type());
    }

    @Test
    void malformedPhoneIsRejectedWithFieldAndCodeOnly() {
        ValidationResult r = check(Raw.withPhone(Raw.create("E1", 1), "98-ABC-7"));
        assertFalse(r.valid());
        assertEquals(new FieldError("phone", ErrorCode.MALFORMED), r.errors().getFirst());
        assertFalse(r.errors().toString().contains("98-ABC-7"), "error report must not contain the value");
    }

    @Test
    void unknownOfficeAndEveryOtherProblemReportedTogether() {
        RawHrRecord bad = new RawHrRecord("UPDATE", "E 1", "zero", "yesterday", "", "not-an-email", "123", "E 1", "XXX", "LUNCH", null, null);
        ValidationResult r = check(bad);
        assertFalse(r.valid());
        var codes = r.errors().stream().map(e -> e.field() + ":" + e.code()).toList();
        assertTrue(codes.contains("employeeId:MALFORMED"), codes.toString());
        assertTrue(codes.contains("version:BAD_VERSION"), codes.toString());
        assertTrue(codes.contains("sourceTime:BAD_TIMESTAMP"), codes.toString());
        assertTrue(codes.contains("name:MISSING"), codes.toString());
        assertTrue(codes.contains("email:MALFORMED"), codes.toString());
        assertTrue(codes.contains("phone:MALFORMED"), codes.toString());
        assertTrue(codes.contains("officeCode:UNKNOWN_OFFICE"), codes.toString());
        assertTrue(codes.contains("shiftCode:UNKNOWN_SHIFT"), codes.toString());
    }

    @Test
    void deactivateNeedsOnlyIdAndVersion() {
        assertTrue(check(Raw.deactivate("E1", 3)).valid());
        assertFalse(check(Raw.deactivate("E1", 0)).valid());
    }

    @Test
    void transportFieldsFromHrAreIgnoredNotRejected() {
        ValidationResult r = check(Raw.withPickup(Raw.create("E1", 1), "Gate 4"));
        assertTrue(r.valid());
        assertEquals(java.util.Set.of(EmployeeField.PICKUP_POINT), r.event().ignoredFields());
    }

    @Test
    void managerCannotBeSelfAndTypeMustBeKnown() {
        RawHrRecord self = new RawHrRecord("CREATE", "E1", "1", null, "Asha Rao", "a@b.co", null, "E1", "BLR1", "GEN", null, null);
        assertTrue(check(self).errors().contains(new FieldError("managerId", ErrorCode.SELF_MANAGER)));
        RawHrRecord bogus = new RawHrRecord("MERGE", "E1", "1", null, "Asha Rao", "a@b.co", null, null, "BLR1", "GEN", null, null);
        assertTrue(check(bogus).errors().contains(new FieldError("type", ErrorCode.BAD_EVENT_TYPE)));
    }
}
