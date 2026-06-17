package com.firstclub.membership.service.impl;

import com.firstclub.membership.entity.AuditEvent;
import com.firstclub.membership.repository.AuditEventRepository;
import com.firstclub.membership.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditServiceImpl implements AuditService {

    private final AuditEventRepository auditRepository;
    private final Clock clock;

    // Both public methods carry REQUIRES_NEW and route through persist() — NOT through each other —
    // so the audit commits independently of the observed operation regardless of which is called.
    // (Delegating one public method to the other would self-invoke the proxy and silently drop the
    // REQUIRES_NEW propagation.)

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String detail) {
        persist(currentActor(), action, detail);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String detail) {
        persist(actor, action, detail);
    }

    private void persist(String actor, String action, String detail) {
        try {
            auditRepository.save(AuditEvent.builder()
                    .actor(actor)
                    .action(action)
                    .detail(detail)
                    .occurredAt(LocalDateTime.now(clock))
                    .build());
        } catch (Exception e) {
            // Auditing must never break the operation it observes.
            log.warn("Failed to write audit event {} for {}", action, actor, e);
        }
    }

    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "anonymous";
    }
}
