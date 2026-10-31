package com.moveinsync.roster.api.controller;

import com.moveinsync.roster.api.dto.HrEventBatch;
import com.moveinsync.roster.security.CurrentUser;
import com.moveinsync.roster.service.RosterService;
import com.moveinsync.roster.service.model.BatchReport;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The near-real-time HR feed. Always 200 with a per-record report when the batch itself is
 * readable; individual records can be APPLIED, UNCHANGED, DUPLICATE, STALE, CONFLICT or REJECTED.
 * Re-sending a batch is safe.
 */
@RestController
@RequestMapping("/api/v1/hr")
@PreAuthorize("hasRole('ADMIN')")
public class HrFeedController {

    private final RosterService roster;

    public HrFeedController(RosterService roster) {
        this.roster = roster;
    }

    @PostMapping("/events")
    public BatchReport ingest(@AuthenticationPrincipal Jwt jwt, @RequestBody HrEventBatch batch) {
        return roster.ingestBatch(CurrentUser.from(jwt).tenant(), batch == null ? null : batch.records());
    }
}
