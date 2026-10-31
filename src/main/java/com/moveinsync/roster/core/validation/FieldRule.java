package com.moveinsync.roster.core.validation;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * One check on one field. Rules are small classes composed by {@link RecordValidator}; a new
 * check is a new rule, not a new branch in a long method.
 */
public abstract class FieldRule {

    protected final String field;
    protected final Function<RawHrRecord, String> getter;

    protected FieldRule(String field, Function<RawHrRecord, String> getter) {
        this.field = field;
        this.getter = getter;
    }

    public String field() { return field; }

    public String rawValue(RawHrRecord r) { return getter.apply(r); }

    /** @param normalised the field value after {@link RecordValidator#normalise}; may be null */
    public abstract Optional<FieldError> check(String normalised, RawHrRecord raw, TenantCatalog catalog);

    protected Optional<FieldError> error(ErrorCode c) { return Optional.of(new FieldError(field, c)); }

    /** Present and matching a pattern (optionally allowed to be absent). */
    public static final class Matches extends FieldRule {
        private final Pattern pattern;
        private final int maxLength;
        private final boolean required;

        public Matches(String field, Function<RawHrRecord, String> getter, String regex, int maxLength, boolean required) {
            super(field, getter);
            this.pattern = Pattern.compile(regex);
            this.maxLength = maxLength;
            this.required = required;
        }

        @Override
        public Optional<FieldError> check(String v, RawHrRecord raw, TenantCatalog c) {
            if (v == null || v.isEmpty()) return required ? error(ErrorCode.MISSING) : Optional.empty();
            if (v.length() > maxLength) return error(ErrorCode.TOO_LONG);
            return pattern.matcher(v).matches() ? Optional.empty() : error(ErrorCode.MALFORMED);
        }
    }

    /** Value must be one of the tenant's known codes. */
    public static final class InCatalog extends FieldRule {
        private final Function<TenantCatalog, java.util.Set<String>> codes;
        private final ErrorCode unknown;

        public InCatalog(String field, Function<RawHrRecord, String> getter,
                         Function<TenantCatalog, java.util.Set<String>> codes, ErrorCode unknown) {
            super(field, getter);
            this.codes = codes;
            this.unknown = unknown;
        }

        @Override
        public Optional<FieldError> check(String v, RawHrRecord raw, TenantCatalog c) {
            if (v == null || v.isEmpty()) return error(ErrorCode.MISSING);
            return codes.apply(c).contains(v) ? Optional.empty() : error(unknown);
        }
    }

    /** A manager cannot be the employee themselves. */
    public static final class NotSelf extends FieldRule {
        public NotSelf(String field, Function<RawHrRecord, String> getter) {
            super(field, getter);
        }

        @Override
        public Optional<FieldError> check(String v, RawHrRecord raw, TenantCatalog c) {
            return v != null && v.equals(raw.employeeId() == null ? null : raw.employeeId().trim())
                    ? error(ErrorCode.SELF_MANAGER) : Optional.empty();
        }
    }
}
