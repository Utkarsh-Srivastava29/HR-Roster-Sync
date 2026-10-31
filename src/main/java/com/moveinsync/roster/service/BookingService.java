package com.moveinsync.roster.service;

import com.moveinsync.roster.core.booking.Booking;
import com.moveinsync.roster.core.booking.BookingStore;
import com.moveinsync.roster.core.booking.Route;
import com.moveinsync.roster.core.store.RosterStore;
import com.moveinsync.roster.service.error.ConflictException;
import com.moveinsync.roster.service.error.InvalidRequestException;
import com.moveinsync.roster.service.error.NotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Minimal booking side so deactivation side effects can be shown end to end. */
public class BookingService {

    private final BookingStore bookings;
    private final RosterStore roster;
    private final Clock clock;

    public BookingService(BookingStore bookings, RosterStore roster, Clock clock) {
        this.bookings = bookings;
        this.roster = roster;
        this.clock = clock;
    }

    public Route upsertRoute(String tenantId, String routeId, int capacity) {
        if (routeId == null || !routeId.matches("[A-Za-z0-9_-]{1,32}") || capacity < 1 || capacity > 100)
            throw new InvalidRequestException("routeId [A-Za-z0-9_-]{1,32}, capacity 1..100");
        return bookings.inBookingTransaction(tenantId, tx -> {
            int taken = tx.route(routeId).map(Route::seatsTaken).orElse(0);
            if (taken > capacity) throw new ConflictException("more seats already booked than the new capacity");
            Route r = new Route(tenantId, routeId, capacity, taken);
            tx.saveRoute(r);
            return r;
        });
    }

    public Booking book(String tenantId, String employeeId, String routeId, Instant pickupAt) {
        var e = roster.find(tenantId, employeeId).filter(x -> x.active() && !x.hr().unknown())
                .orElseThrow(() -> new NotFoundException("active employee not found"));
        if (pickupAt == null || !pickupAt.isAfter(clock.instant())) throw new InvalidRequestException("pickupAt must be in the future");
        return bookings.inBookingTransaction(tenantId, tx -> {
            Route r = tx.route(routeId).orElseThrow(() -> new NotFoundException("route not found"));
            if (r.seatsFree() == 0) throw new ConflictException("no free seat on this route");
            tx.saveRoute(r.take());
            Booking b = new Booking(tenantId, UUID.randomUUID().toString(), e.employeeId(), routeId, pickupAt, Booking.Status.BOOKED);
            tx.saveBooking(b);
            return b;
        });
    }

    public List<Booking> bookingsOf(String tenantId, String employeeId) {
        return bookings.bookingsOf(tenantId, employeeId);
    }

    public Route route(String tenantId, String routeId) {
        return bookings.route(tenantId, routeId).orElseThrow(() -> new NotFoundException("route not found"));
    }
}
