package com.moveinsync.roster.core.validation;

import com.moveinsync.roster.core.domain.HrEvent;

import java.util.List;

/** Either a valid event or the list of field errors. */
public record ValidationResult(HrEvent event, List<FieldError> errors) {
    public ValidationResult {
        errors = List.copyOf(errors);
    }

    public boolean valid() { return event != null && errors.isEmpty(); }

    public static ValidationResult ok(HrEvent e) { return new ValidationResult(e, List.of()); }

    public static ValidationResult invalid(List<FieldError> errs) { return new ValidationResult(null, errs); }
}
