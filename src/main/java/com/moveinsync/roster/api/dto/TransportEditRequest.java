package com.moveinsync.roster.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/** @param expectedVersion the transportVersion you last read (optimistic concurrency) */
public record TransportEditRequest(@Size(max = 200) String pickupPoint,
                                   @Size(max = 10) List<@Size(max = 40) String> specialAssistance,
                                   @NotNull @PositiveOrZero Long expectedVersion) {}
