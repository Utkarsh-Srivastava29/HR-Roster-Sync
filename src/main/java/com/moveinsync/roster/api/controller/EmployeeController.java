package com.moveinsync.roster.api.controller;

import com.moveinsync.roster.api.dto.EmployeeView;
import com.moveinsync.roster.api.dto.TransportEditRequest;
import com.moveinsync.roster.core.booking.Booking;
import com.moveinsync.roster.core.domain.TransportEdit;
import com.moveinsync.roster.security.CurrentUser;
import com.moveinsync.roster.service.BookingService;
import com.moveinsync.roster.service.RosterService;
import com.moveinsync.roster.service.error.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class EmployeeController {

    private final RosterService roster;
    private final BookingService bookings;

    public EmployeeController(RosterService roster, BookingService bookings) {
        this.roster = roster;
        this.bookings = bookings;
    }

    @GetMapping("/employees")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public List<EmployeeView> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String after,
                                   @RequestParam(defaultValue = "100") int limit) {
        return roster.list(CurrentUser.from(jwt).tenant(), after, limit).stream().map(EmployeeView::of).toList();
    }

    @GetMapping("/employees/{employeeId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public EmployeeView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String employeeId) {
        return EmployeeView.of(roster.get(CurrentUser.from(jwt).tenant(), employeeId));
    }

    /** Transport-owned fields. HR can never change these; this endpoint can never change HR fields. */
    @PatchMapping("/employees/{employeeId}/transport")
    @PreAuthorize("hasRole('OPERATOR')")
    public EmployeeView editTransport(@AuthenticationPrincipal Jwt jwt, @PathVariable String employeeId,
                                      @Valid @RequestBody TransportEditRequest r) {
        return EmployeeView.of(roster.editTransport(CurrentUser.from(jwt).tenant(), employeeId,
                new TransportEdit(r.pickupPoint(), r.specialAssistance(), r.expectedVersion())));
    }

    @GetMapping("/employees/{employeeId}/bookings")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public List<Booking> bookingsOf(@AuthenticationPrincipal Jwt jwt, @PathVariable String employeeId) {
        return bookings.bookingsOf(CurrentUser.from(jwt).tenant(), employeeId);
    }

    /** An employee sees only their own record. */
    @GetMapping("/me")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public EmployeeView me(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt);
        if (u.employeeRef() == null) throw new NotFoundException("employee not found");
        return EmployeeView.of(roster.get(u.tenant(), u.employeeRef()));
    }

    @GetMapping("/me/bookings")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<Booking> myBookings(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt);
        if (u.employeeRef() == null) throw new NotFoundException("employee not found");
        return bookings.bookingsOf(u.tenant(), u.employeeRef());
    }
}
