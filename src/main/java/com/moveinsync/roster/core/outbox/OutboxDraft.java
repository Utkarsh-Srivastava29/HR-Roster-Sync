package com.moveinsync.roster.core.outbox;

import com.moveinsync.roster.core.domain.ChangeSource;
import com.moveinsync.roster.core.domain.ChangeType;
import com.moveinsync.roster.core.domain.EmployeeField;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A change event to be written in the same transaction as the employee row. It carries ids,
 * field names and versions only - no personal data - so it is safe to log, store and fan out.
 * Consumers that need values read them through the API.
 */
public record OutboxDraft(String eventId, String tenantId, String employeeId, ChangeType type,
                          List<String> changedFields, long hrVersion, long transportVersion,
                          ChangeSource source, Instant occurredAt) {

    public OutboxDraft {
        changedFields = List.copyOf(changedFields);
    }

    public static OutboxDraft of(String tenantId, String employeeId, ChangeType type, Set<EmployeeField> changed,
                                 long hrVersion, long transportVersion, ChangeSource source, Instant now) {
        return new OutboxDraft(UUID.randomUUID().toString(), tenantId, employeeId, type,
                changed.stream().map(Enum::name).sorted().toList(), hrVersion, transportVersion, source, now);
    }
}
