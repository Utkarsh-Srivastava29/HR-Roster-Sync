package com.moveinsync.roster.core.booking;

/** A cab route with a fixed number of seats. */
public record Route(String tenantId, String routeId, int capacity, int seatsTaken) {

    public Route {
        if (capacity < 0 || seatsTaken < 0 || seatsTaken > capacity) throw new IllegalArgumentException("bad seat counts");
    }

    public int seatsFree() { return capacity - seatsTaken; }

    public Route take() { return new Route(tenantId, routeId, capacity, seatsTaken + 1); }

    public Route release() { return new Route(tenantId, routeId, capacity, Math.max(0, seatsTaken - 1)); }
}
