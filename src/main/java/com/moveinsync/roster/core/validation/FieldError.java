package com.moveinsync.roster.core.validation;

/** One problem with one field. Carries the field name and a code, never the value. */
public record FieldError(String field, ErrorCode code) {}
