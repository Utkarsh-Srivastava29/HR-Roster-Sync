package com.moveinsync.roster.core.validation;

import com.moveinsync.roster.core.domain.EmployeeField;
import com.moveinsync.roster.core.domain.HrEvent;
import com.moveinsync.roster.core.domain.HrProfile;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Validates one raw record at the boundary and turns it into an {@link HrEvent}. Every field
 * is checked (not just the first error) so the per-record report is complete. One bad record
 * only ever affects itself.
 */
public final class RecordValidator {

    public static final String ID_REGEX = "[A-Za-z0-9_-]{1,32}";

    private final List<FieldRule> profileRules = List.of(
            new FieldRule.Matches("name", RawHrRecord::name, "[\\p{L}][\\p{L} .'-]*", 100, true),
            new FieldRule.Matches("email", RawHrRecord::email, "[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}", 254, true),
            new FieldRule.Matches("phone", RawHrRecord::phone, "\\+[1-9][0-9]{7,14}", 16, false),
            new FieldRule.Matches("managerId", RawHrRecord::managerId, ID_REGEX, 32, false),
            new FieldRule.NotSelf("managerId", RawHrRecord::managerId),
            new FieldRule.InCatalog("officeCode", RawHrRecord::officeCode, TenantCatalog::officeCodes, ErrorCode.UNKNOWN_OFFICE),
            new FieldRule.InCatalog("shiftCode", RawHrRecord::shiftCode, TenantCatalog::shiftCodes, ErrorCode.UNKNOWN_SHIFT));

    private final FieldRule idRule = new FieldRule.Matches("employeeId", RawHrRecord::employeeId, ID_REGEX, 32, true);

    public ValidationResult validate(String tenantId, RawHrRecord raw, TenantCatalog catalog, Instant now) {
        List<FieldError> errors = new ArrayList<>();
        if (raw == null) return ValidationResult.invalid(List.of(new FieldError("record", ErrorCode.MISSING)));

        String id = normalise("employeeId", raw.employeeId());
        idRule.check(id, raw, catalog).ifPresent(errors::add);

        HrEvent.Type type = null;
        String t = normalise("type", raw.type());
        if (t == null) errors.add(new FieldError("type", ErrorCode.MISSING));
        else {
            try { type = HrEvent.Type.valueOf(t); } catch (IllegalArgumentException e) { errors.add(new FieldError("type", ErrorCode.BAD_EVENT_TYPE)); }
        }

        long version = 0;
        String v = normalise("version", raw.version());
        if (v == null) errors.add(new FieldError("version", ErrorCode.MISSING));
        else {
            try {
                version = Long.parseLong(v);
                if (version < 1) errors.add(new FieldError("version", ErrorCode.BAD_VERSION));
            } catch (NumberFormatException e) { errors.add(new FieldError("version", ErrorCode.BAD_VERSION)); }
        }

        Instant sourceTime = now;
        String ts = normalise("sourceTime", raw.sourceTime());
        if (ts != null) {
            try { sourceTime = Instant.parse(ts); } catch (DateTimeParseException e) { errors.add(new FieldError("sourceTime", ErrorCode.BAD_TIMESTAMP)); }
        }

        HrProfile profile = null;
        if (type != HrEvent.Type.DEACTIVATE) {
            for (FieldRule r : profileRules) {
                Optional<FieldError> fe = r.check(normalise(r.field(), r.rawValue(raw)), raw, catalog);
                fe.ifPresent(err -> { if (!errors.contains(err)) errors.add(err); });
            }
            if (errors.isEmpty()) {
                profile = new HrProfile(
                        normalise("name", raw.name()), normalise("email", raw.email()), normalise("phone", raw.phone()),
                        normalise("managerId", raw.managerId()), normalise("officeCode", raw.officeCode()),
                        normalise("shiftCode", raw.shiftCode()));
            }
        }
        if (!errors.isEmpty() || type == null) return ValidationResult.invalid(errors);

        Set<EmployeeField> ignored = EnumSet.noneOf(EmployeeField.class);
        if (notBlank(raw.pickupPoint())) ignored.add(EmployeeField.PICKUP_POINT);
        if (notBlank(raw.specialAssistance())) ignored.add(EmployeeField.SPECIAL_ASSISTANCE);
        return ValidationResult.ok(new HrEvent(tenantId, type, id, version, sourceTime, profile, ignored));
    }

    /** Trim, collapse spaces; lower-case e-mails; strip formatting from phones; upper-case codes. Blank = null. */
    public static String normalise(String field, String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty()) return null;
        return switch (field) {
            case "name" -> s.replaceAll("\\s+", " ");
            case "email" -> s.toLowerCase(Locale.ROOT);
            case "phone" -> s.replaceAll("[\\s()-]", "");
            case "officeCode", "shiftCode", "type" -> s.toUpperCase(Locale.ROOT);
            default -> s;
        };
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
