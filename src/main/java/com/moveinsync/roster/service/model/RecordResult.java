package com.moveinsync.roster.service.model;

import com.moveinsync.roster.core.ingest.Outcome;
import com.moveinsync.roster.core.validation.FieldError;

import java.util.List;

/**
 * Per-record line of a batch report: position in the batch, the employee id (only if it was
 * well-formed), the outcome, field errors (field + code, never the value), changed fields and
 * transport-owned fields that were sent by HR and ignored.
 */
public record RecordResult(int index, String employeeId, Outcome outcome, List<FieldError> errors,
                           List<String> changedFields, List<String> ignoredFields, String reason) {}
