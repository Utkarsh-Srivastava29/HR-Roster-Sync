package com.moveinsync.roster.security;

/**
 * <ul>
 *   <li>EMPLOYEE - reads their own record and bookings only</li>
 *   <li>OPERATOR - MoveInSync transport admin: edits pickup point / special assistance, books seats,
 *       reads employees and change feed, approves or rejects braked reconciliations</li>
 *   <li>ADMIN - the client's HR integration / HR admin: pushes HR events, uploads the nightly export,
 *       manages office and shift codes; can read everything an operator reads</li>
 * </ul>
 */
public enum Role { EMPLOYEE, OPERATOR, ADMIN }
