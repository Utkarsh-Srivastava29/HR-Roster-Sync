package com.moveinsync.roster.api.controller;

import com.moveinsync.roster.security.CurrentUser;
import com.moveinsync.roster.service.ReconciliationService;
import com.moveinsync.roster.service.model.ReconciliationRun;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * Nightly export upload and the operator's approve / reject decision for braked runs. The body
 * is streamed straight to disk ({@code Content-Type: text/csv}); it is never held in memory.
 */
@RestController
@RequestMapping("/api/v1/reconciliations")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PostMapping(consumes = {"text/csv", "application/octet-stream", "text/plain"})
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReconciliationRun> upload(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) throws IOException {
        ReconciliationRun run = service.submit(CurrentUser.from(jwt).tenant(), request.getInputStream());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public List<ReconciliationRun> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(CurrentUser.from(jwt).tenant());
    }

    @GetMapping("/{runId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ReconciliationRun get(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId) {
        return service.get(CurrentUser.from(jwt).tenant(), runId);
    }

    @PostMapping("/{runId}/approve")
    @PreAuthorize("hasRole('OPERATOR')")
    public ReconciliationRun approve(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId) {
        CurrentUser u = CurrentUser.from(jwt);
        return service.approve(u.tenant(), runId, u.username());
    }

    @PostMapping("/{runId}/reject")
    @PreAuthorize("hasRole('OPERATOR')")
    public ReconciliationRun reject(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId) {
        CurrentUser u = CurrentUser.from(jwt);
        return service.reject(u.tenant(), runId, u.username());
    }
}
