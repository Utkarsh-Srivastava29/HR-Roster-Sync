package com.moveinsync.roster.core.domain;

import java.util.List;

/** Owned by the transport admin. Both fields are optional. */
public record TransportProfile(String pickupPoint, List<String> specialAssistance) {

    public static final TransportProfile EMPTY = new TransportProfile(null, List.of());

    public TransportProfile {
        specialAssistance = specialAssistance == null ? List.of() : List.copyOf(specialAssistance);
    }

    @Override
    public String toString() {
        return "TransportProfile[pickupSet=" + (pickupPoint != null) + ", assistance=" + specialAssistance.size() + "]";
    }
}
