package com.moveinsync.roster.api.dto;

import com.moveinsync.roster.core.domain.Employee;

import java.time.Instant;
import java.util.List;

/** What the API returns for an employee. Personal fields are returned to authorised callers only, never logged. */
public record EmployeeView(String employeeId, String status, Hr hr, Transport transport, Instant updatedAt) {

    public record Hr(String name, String email, String phone, String managerId, String officeCode, String shiftCode,
                     long version, Instant sourceTime) {}

    public record Transport(String pickupPoint, List<String> specialAssistance, long version) {}

    public static EmployeeView of(Employee e) {
        var p = e.hr();
        return new EmployeeView(e.employeeId(), e.status().name(),
                new Hr(p.name(), p.email(), p.phone(), p.managerId(), p.officeCode(), p.shiftCode(), e.hrVersion(), e.hrSourceTime()),
                new Transport(e.transport().pickupPoint(), e.transport().specialAssistance(), e.transportVersion()),
                e.updatedAt());
    }
}
