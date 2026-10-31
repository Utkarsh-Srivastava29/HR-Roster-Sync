package com.moveinsync.roster.api.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, String role, String tenant) {}
