package com.moveinsync.roster.api.controller;

import com.moveinsync.roster.api.dto.BookingRequest;
import com.moveinsync.roster.api.dto.CatalogRequest;
import com.moveinsync.roster.api.dto.RouteRequest;
import com.moveinsync.roster.config.AppConfig;
import com.moveinsync.roster.core.booking.Booking;
import com.moveinsync.roster.core.booking.Route;
import com.moveinsync.roster.core.outbox.OutboxEvent;
import com.moveinsync.roster.core.validation.TenantCatalog;
import com.moveinsync.roster.security.CurrentUser;
import com.moveinsync.roster.service.BookingService;
import com.moveinsync.roster.service.CatalogService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reference data, routes and bookings, and the change feed. */
@RestController
@RequestMapping("/api/v1")
public class OperationsController {

    private final CatalogService catalogs;
    private final BookingService bookings;
    private final AppConfig.Stores stores;

    public OperationsController(CatalogService catalogs, BookingService bookings, AppConfig.Stores stores) {
        this.catalogs = catalogs;
        this.bookings = bookings;
        this.stores = stores;
    }

    @GetMapping("/catalog")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public TenantCatalog catalog(@AuthenticationPrincipal Jwt jwt) {
        return catalogs.get(CurrentUser.from(jwt).tenant());
    }

    @PutMapping("/catalog")
    @PreAuthorize("hasRole('ADMIN')")
    public TenantCatalog putCatalog(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CatalogRequest r) {
        return catalogs.put(CurrentUser.from(jwt).tenant(), r.offices(), r.shifts());
    }

    @PutMapping("/routes/{routeId}")
    @PreAuthorize("hasRole('OPERATOR')")
    public Route upsertRoute(@AuthenticationPrincipal Jwt jwt, @PathVariable String routeId, @Valid @RequestBody RouteRequest r) {
        return bookings.upsertRoute(CurrentUser.from(jwt).tenant(), routeId, r.capacity());
    }

    @GetMapping("/routes/{routeId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Route route(@AuthenticationPrincipal Jwt jwt, @PathVariable String routeId) {
        return bookings.route(CurrentUser.from(jwt).tenant(), routeId);
    }

    @PostMapping("/bookings")
    @PreAuthorize("hasRole('OPERATOR')")
    public Booking book(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BookingRequest r) {
        return bookings.book(CurrentUser.from(jwt).tenant(), r.employeeId(), r.routeId(), r.pickupAt());
    }

    /** Downstream systems can also poll this feed (ids, field names and versions; no personal data). */
    @GetMapping("/changes")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public List<OutboxEvent> changes(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") long after,
                                     @RequestParam(defaultValue = "100") int limit) {
        return stores.roster().changesAfter(CurrentUser.from(jwt).tenant(), after, Math.max(1, Math.min(limit, 1000)));
    }

    /** Outbox health (all tenants share one relay, so these are global numbers, not tenant data). */
    @GetMapping("/outbox/status")
    @PreAuthorize("hasRole('OPERATOR')")
    public Map<String, Object> outbox() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unpublished", stores.roster().unpublishedCount());
        m.put("oldestUnpublishedAgeSeconds", stores.roster().oldestUnpublishedAt()
                .map(t -> Duration.between(t, Instant.now()).toSeconds()).orElse(0L));
        return m;
    }
}
