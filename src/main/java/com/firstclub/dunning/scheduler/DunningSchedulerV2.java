package com.firstclub.dunning.scheduler;

import com.firstclub.dunning.service.DunningServiceV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job that processes due v2 policy-driven dunning attempts.
 *
 * <p>Runs every 5 minutes (offset by 15 s from the v1 {@link DunningScheduler})
 * to avoid database lock contention when both schedulers fire simultaneously.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DunningSchedulerV2 {

    private final DunningServiceV2 dunningServiceV2;

    /** Every 5 minutes; initial delay 105 s. */
    @Scheduled(fixedRate = 300_000, initialDelay = 105_000)
    public void runDunningV2() {
        log.debug("Dunning v2 scheduler: checking for due policy-driven attempts");
        dunningServiceV2.processDueV2Attempts();
    }
}
