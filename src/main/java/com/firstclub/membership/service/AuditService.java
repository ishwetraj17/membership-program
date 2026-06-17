package com.firstclub.membership.service;

/**
 * Records audit events. Each record commits in its own transaction so the trail survives even
 * when the surrounding business transaction rolls back (e.g. a failed login).
 */
public interface AuditService {

    /** Record using the current authenticated principal as the actor. */
    void record(String action, String detail);

    /** Record with an explicit actor (e.g. a login attempt before authentication). */
    void record(String actor, String action, String detail);
}
