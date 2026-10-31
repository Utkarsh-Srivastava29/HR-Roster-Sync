package com.moveinsync.roster.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record BookingRequest(@NotBlank String employeeId, @NotBlank String routeId, @NotNull Instant pickupAt) {}
