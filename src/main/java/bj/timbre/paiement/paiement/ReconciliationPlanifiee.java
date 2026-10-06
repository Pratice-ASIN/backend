package bj.timbre.paiement.paiement;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Déclenche périodiquement la réconciliation (désactivable : paiement.reconciliation.actif=false). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "paiement.reconciliation.actif", havingValue = "true")
public class ReconciliationPlanifiee {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationPlanifiee.class);

    private final ReconciliationService reconciliation;
    private final Clock clock;

    public ReconciliationPlanifiee(ReconciliationService reconciliation, Clock clock) {
        this.reconciliation = reconciliation;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paiement.reconciliation.intervalle}",
               initialDelayString = "${paiement.reconciliation.intervalle}")
    public void executer() {
        int n = reconciliation.reconcilier(clock.instant());
        if (n > 0) {
            log.info("Réconciliation : {} paiement(s) finalisé(s)", n);
        }
    }
}
