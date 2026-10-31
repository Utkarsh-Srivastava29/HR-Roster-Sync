package com.moveinsync.roster.core.reconcile;

import com.moveinsync.roster.core.validation.FieldError;

import java.util.List;

/** A rejected export row: line number, employee id (only if it was well-formed) and field codes. */
public record RowError(long lineNo, String employeeId, List<FieldError> errors) {}
