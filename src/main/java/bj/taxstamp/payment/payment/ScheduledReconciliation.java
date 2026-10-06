package bj.taxstamp.payment.payment;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Déclenche périodiquement la réconciliation (désactivable : payment.reconciliation.enabled=false). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payment.reconciliation.enabled", havingValue = "true")
public class ScheduledReconciliation {

    private static final Logger log = LoggerFactory.getLogger(ScheduledReconciliation.class);

    private final ReconciliationService reconciliation;
    private final Clock clock;

    public ScheduledReconciliation(ReconciliationService reconciliation, Clock clock) {
        this.reconciliation = reconciliation;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payment.reconciliation.interval}",
               initialDelayString = "${payment.reconciliation.interval}")
    public void execute() {
        int n = reconciliation.reconcile(clock.instant());
        if (n > 0) {
            log.info("Réconciliation : {} paiement(s) finalisé(s)", n);
        }
    }
}
