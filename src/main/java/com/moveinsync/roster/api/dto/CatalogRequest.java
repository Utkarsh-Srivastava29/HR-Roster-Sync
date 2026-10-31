package com.moveinsync.roster.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CatalogRequest(@NotNull @Size(max = 1000) Set<String> offices, @NotNull @Size(max = 100) Set<String> shifts) {}
