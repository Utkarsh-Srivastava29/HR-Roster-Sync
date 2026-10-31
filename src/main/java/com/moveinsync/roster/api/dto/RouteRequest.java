package com.moveinsync.roster.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record RouteRequest(@Min(1) @Max(100) int capacity) {}
