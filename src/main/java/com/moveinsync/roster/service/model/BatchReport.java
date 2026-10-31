package com.moveinsync.roster.service.model;

import java.util.List;
import java.util.Map;

/** Result of a batch: totals by outcome and one line per record. One bad record never fails the batch. */
public record BatchReport(int received, Map<String, Integer> totals, List<RecordResult> results) {}
