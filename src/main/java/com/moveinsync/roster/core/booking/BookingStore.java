package com.moveinsync.roster.core.booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Bookings, seats, and the consumer's "already processed" record, changed atomically. */
public interface BookingStore {

    <T> T inBookingTransaction(String tenantId, BookingWork<T> work);

    @FunctionalInterface
    interface BookingWork<T> { T run(BookingTx tx); }

    interface BookingTx {
        /** @return false if this consumer already processed this event (idempotent consumer) */
        boolean markProcessed(String consumer, String eventId);
        List<Booking> futureBookings(String employeeId, Instant after);
        Optional<Route> route(String routeId);
        void saveRoute(Route r);
        void saveBooking(Booking b);
    }

    List<Booking> bookingsOf(String tenantId, String employeeId);

    Optional<Route> route(String tenantId, String routeId);
}
