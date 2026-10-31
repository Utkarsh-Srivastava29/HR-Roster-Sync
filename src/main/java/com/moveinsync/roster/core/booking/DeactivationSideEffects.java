package com.moveinsync.roster.core.booking;

import com.moveinsync.roster.core.domain.ChangeType;
import com.moveinsync.roster.core.outbox.OutboxEvent;

import java.time.Clock;
import java.util.List;

/**
 * Downstream consumer: when an employee is deactivated, cancel their future bookings and free
 * their seats. Idempotent on {@code eventId} (recorded in the same transaction as the
 * cancellations), so a redelivered event changes nothing.
 */
public final class DeactivationSideEffects implements EventSubscriber {

    public static final String CONSUMER = "booking-deactivation";

    private final BookingStore bookings;
    private final Clock clock;

    public DeactivationSideEffects(BookingStore bookings, Clock clock) {
        this.bookings = bookings;
        this.clock = clock;
    }

    @Override
    public void onEvent(OutboxEvent e) {
        if (e.draft().type() != ChangeType.DEACTIVATED) return;
        bookings.inBookingTransaction(e.tenantId(), tx -> {
            if (!tx.markProcessed(CONSUMER, e.eventId())) return 0;
            List<Booking> future = tx.futureBookings(e.employeeId(), clock.instant());
            for (Booking b : future) {
                tx.saveBooking(b.cancelled());
                tx.route(b.routeId()).ifPresent(r -> tx.saveRoute(r.release()));
            }
            return future.size();
        });
    }
}
