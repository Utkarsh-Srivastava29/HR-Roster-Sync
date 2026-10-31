package com.moveinsync.roster.api.dto;

import com.moveinsync.roster.core.validation.RawHrRecord;

import java.util.List;

/**
 * Body of {@code POST /api/v1/hr/events}. Records are taken as raw strings on purpose: a bad value
 * must become a per-record error in the report, not a 400 for the whole batch.
 */
public record HrEventBatch(List<RawHrRecord> records) {}
