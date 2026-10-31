package com.moveinsync.roster.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * The HR-owned part of an employee, already validated and normalised. HR events carry the
 * whole profile (snapshot semantics), which is what makes "ignore anything older" correct.
 */
public record HrProfile(String name, String email, String phone, String managerId, String officeCode, String shiftCode) {

    /**
     * Placeholder for an employee we only know as deactivated (the DEACTIVATE arrived before
     * the CREATE). Keeps the version so the late CREATE is correctly ignored as stale.
     */
    public static final HrProfile UNKNOWN = new HrProfile("", "", null, null, "", "");

    public boolean unknown() { return name.isEmpty() && officeCode.isEmpty(); }

    /** Fields whose value differs between {@code this} and {@code other}. */
    public java.util.Set<EmployeeField> diff(HrProfile other) {
        java.util.Set<EmployeeField> d = java.util.EnumSet.noneOf(EmployeeField.class);
        if (!java.util.Objects.equals(name, other.name)) d.add(EmployeeField.NAME);
        if (!java.util.Objects.equals(email, other.email)) d.add(EmployeeField.EMAIL);
        if (!java.util.Objects.equals(phone, other.phone)) d.add(EmployeeField.PHONE);
        if (!java.util.Objects.equals(managerId, other.managerId)) d.add(EmployeeField.MANAGER_ID);
        if (!java.util.Objects.equals(officeCode, other.officeCode)) d.add(EmployeeField.OFFICE_CODE);
        if (!java.util.Objects.equals(shiftCode, other.shiftCode)) d.add(EmployeeField.SHIFT_CODE);
        return d;
    }

    public HrProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(officeCode, "officeCode");
        Objects.requireNonNull(shiftCode, "shiftCode");
    }

    /**
     * 64-bit content hash of the normalised profile (first 8 bytes of SHA-256). Equal profiles
     * have equal hashes on every machine and in every run; used for duplicate detection and
     * for the nightly diff.
     */
    public long contentHash() {
        String canonical = String.join("\u001f", name, email, nz(phone), nz(managerId), officeCode, shiftCode);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xff);
            return h;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Never print personal data. */
    @Override
    public String toString() {
        return "HrProfile[office=" + officeCode + ", shift=" + shiftCode + "]";
    }
}
