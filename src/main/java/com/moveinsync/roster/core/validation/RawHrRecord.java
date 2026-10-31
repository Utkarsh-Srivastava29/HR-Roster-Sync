package com.moveinsync.roster.core.validation;

/**
 * A record exactly as received (API JSON or one CSV row), before validation. All strings.
 * {@code pickupPoint} / {@code specialAssistance} are transport-owned: if HR sends them they are
 * ignored, never applied.
 */
public record RawHrRecord(String type,
                          String employeeId,
                          String version,
                          String sourceTime,
                          String name,
                          String email,
                          String phone,
                          String managerId,
                          String officeCode,
                          String shiftCode,
                          String pickupPoint,
                          String specialAssistance) {

    @Override
    public String toString() {
        return "RawHrRecord[employeeId=" + employeeId + ", type=" + type + ", version=" + version + "]";
    }
}
