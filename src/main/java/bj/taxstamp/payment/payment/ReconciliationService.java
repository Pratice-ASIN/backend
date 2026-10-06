package bj.taxstamp.payment.payment;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import bj.taxstamp.payment.operator.OperatorStatusQuery;
import bj.taxstamp.payment.operator.OperatorClient;

/**
 * Traite les paiements dont le résultat n'arrive pas (rappel perdu, accusé perdu,
 * opérateur muet).
 *
 * <ul>
 *   <li>Après {@code status-check-delay} : on interroge l'opérateur et on applique
 *       l'état qu'il connaît (succès, échec, ou débit jamais reçu).</li>
 *   <li>Après {@code expiry-delay}, si le débit est toujours en attente : on en
 *       demande l'annulation. Le paiement ne passe EXPIRED que si l'opérateur confirme
 *       l'annulation : on ne libère jamais la demande tant qu'un débit peut encore
 *       aboutir, sinon un nouvel essai pourrait provoquer un double débit.</li>
 * </ul>
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentRepository payments;
    private final PaymentTransactions transactions;
    private final OperatorClient operatorClient;
    private final PaymentProperties properties;

    public ReconciliationService(PaymentRepository payments, PaymentTransactions transactions,
                                 OperatorClient operatorClient, PaymentProperties properties) {
        this.payments = payments;
        this.transactions = transactions;
        this.operatorClient = operatorClient;
        this.properties = properties;
    }

    /** @return le nombre de paiements finalisés lors de ce passage. */
    public int reconcile(Instant now) {
        List<Payment> pending = payments.findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                PaymentStatus.PENDING, now.minus(properties.statusCheckDelay()));
        int completed = 0;
        for (Payment p : pending) {
            try {
                if (reconcile(p, now)) {
                    completed++;
                }
            } catch (RuntimeException e) {
                // Opérateur injoignable : on retentera au prochain passage.
                log.warn("Réconciliation du paiement {} reportée : {}", p.getId(), e.getMessage());
            }
        }
        return completed;
    }

    private boolean reconcile(Payment p, Instant now) {
        String reference = p.getId().toString();
        OperatorStatusQuery state = operatorClient.queryStatus(p.getOperator(), reference);

        if (state.state() == OperatorStatusQuery.State.PENDING) {
            boolean expired = p.getCreatedAt().isBefore(now.minus(properties.expiryDelay()));
            if (!expired) {
                return false;
            }
            state = operatorClient.cancel(p.getOperator(), reference);
        }
        return apply(p, state);
    }

    private boolean apply(Payment p, OperatorStatusQuery state) {
        return switch (state.state()) {
            case SUCCESS -> transactions.applyResult(p.getId(), PaymentStatus.SUCCEEDED,
                    state.operatorReference(), null).isPresent();
            case FAILURE -> transactions.applyResult(p.getId(), PaymentStatus.FAILED,
                    state.operatorReference(), state.reason()).isPresent();
            case UNKNOWN -> transactions.applyResult(p.getId(), PaymentStatus.FAILED,
                    null, "Débit jamais reçu par l'opérateur").isPresent();
            case CANCELLED -> transactions.applyResult(p.getId(), PaymentStatus.EXPIRED,
                    state.operatorReference(), "Aucun résultat de l'opérateur : débit annulé").isPresent();
            case PENDING -> false; // annulation refusée : on garde la main, prochain passage.
        };
    }
}
