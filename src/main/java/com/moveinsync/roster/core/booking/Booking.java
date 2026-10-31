package com.moveinsync.roster.core.booking;

import java.time.Instant;

/** A seat on a route for one pickup. */
public record Booking(String tenantId, String bookingId, String employeeId, String routeId, Instant pickupAt, Status status) {

    public enum Status { BOOKED, CANCELLED }

    public Booking cancelled() {
        return new Booking(tenantId, bookingId, employeeId, routeId, pickupAt, Status.CANCELLED);
    }
}
