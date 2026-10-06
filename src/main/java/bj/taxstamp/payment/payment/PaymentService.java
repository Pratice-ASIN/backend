package bj.taxstamp.payment.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bj.taxstamp.payment.common.BusinessError;
import bj.taxstamp.payment.request.DocumentRequestRepository;
import bj.taxstamp.payment.operator.Acknowledgement;
import bj.taxstamp.payment.operator.DebitRejectedException;
import bj.taxstamp.payment.operator.DebitRequest;
import bj.taxstamp.payment.operator.OperatorClient;
import bj.taxstamp.payment.operator.OperatorUnavailableException;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final int MAX_KEY_LENGTH = 100;
    private static final int RESOLUTION_ATTEMPTS = 20;
    private static final long RESOLUTION_PAUSE_MS = 50;

    private final PaymentTransactions transactions;
    private final PaymentRepository payments;
    private final DocumentRequestRepository requests;
    private final OperatorClient operatorClient;

    public PaymentService(PaymentTransactions transactions, PaymentRepository payments,
                          DocumentRequestRepository requests, OperatorClient operatorClient) {
        this.transactions = transactions;
        this.payments = payments;
        this.requests = requests;
        this.operatorClient = operatorClient;
    }

    public record StartResult(Payment payment, boolean created) {
    }

    /**
     * Lance le paiement d'une demande.
     *
     * <ol>
     *   <li>Validations (aucun débit pour un paiement invalide).</li>
     *   <li>Enregistrement PENDING en transaction ; la base garantit un seul paiement
     *       actif par demande, même pour deux requêtes simultanées.</li>
     *   <li>Seul le gagnant, après commit, demande le débit à l'opérateur.</li>
     * </ol>
     */
    public StartResult start(String userId, UUID requestId, String rawPhoneNumber, MobileOperator operator,
                             String idempotencyKey) {
        String phoneNumber = rawPhoneNumber == null ? null : rawPhoneNumber.trim();
        if (!PhoneNumber.isValid(phoneNumber)) {
            throw new BusinessError(HttpStatus.BAD_REQUEST, "INVALID_PHONE_NUMBER",
                    "Le numéro doit comporter 10 chiffres et commencer par 01");
        }
        if (operator == null) {
            throw new BusinessError(HttpStatus.BAD_REQUEST, "INVALID_OPERATOR", "Opérateur obligatoire");
        }
        String key = normalizeKey(idempotencyKey);

        PaymentTransactions.PaymentCreation creation;
        try {
            creation = transactions.create(userId, requestId, phoneNumber, operator, key);
        } catch (DataAccessException concurrentRace) {
            // Contrainte unique violée (ou verrou) : une requête concurrente a gagné.
            // Aucun débit n'a été demandé par ce fil d'exécution.
            log.info("Requête de paiement concurrente détectée pour la demande {}", requestId);
            return new StartResult(awaitWinner(userId, requestId, phoneNumber, operator, key), false);
        }

        if (!creation.created()) {
            return new StartResult(creation.payment(), false);
        }
        requestDebit(creation.payment());
        return new StartResult(transactions.reload(creation.payment().getId()), true);
    }

    /**
     * Selon la base, la violation peut être signalée avant que la transaction gagnante
     * soit validée : on lui laisse quelques instants pour devenir visible.
     */
    private Payment awaitWinner(String userId, UUID requestId, String phoneNumber, MobileOperator operator,
                                String key) {
        for (int attempt = 0; attempt < RESOLUTION_ATTEMPTS; attempt++) {
            Optional<Payment> winner = transactions.resolveAfterConflict(userId, requestId, phoneNumber, operator, key);
            if (winner.isPresent()) {
                return winner.get();
            }
            try {
                Thread.sleep(RESOLUTION_PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new BusinessError(HttpStatus.CONFLICT, "CONCURRENT_CONFLICT",
                "Une autre demande de paiement a été traitée au même moment, veuillez réessayer");
    }

    private void requestDebit(Payment p) {
        DebitRequest request = new DebitRequest(p.getOperator(), p.getId().toString(), p.getPhoneNumber(), p.getAmount());
        try {
            Acknowledgement ack = operatorClient.requestDebit(request);
            transactions.recordAcknowledgement(p.getId(), ack.operatorReference());
        } catch (DebitRejectedException rejection) {
            transactions.applyResult(p.getId(), PaymentStatus.FAILED, null,
                    "Refusé par l'opérateur : " + rejection.getMessage());
        } catch (OperatorUnavailableException uncertain) {
            // Issue inconnue : surtout NE PAS marquer en échec (le débit a pu être pris en compte).
            // Le paiement reste PENDING ; le résultat signé ou la réconciliation trancheront.
            log.warn("Débit {} sans accusé de réception ({}), réconciliation à venir",
                    p.getId(), uncertain.getMessage());
        } catch (RuntimeException uncertain) {
            // Même règle pour une erreur inattendue : issue inconnue, on ne conclut pas.
            log.error("Erreur inattendue lors de la demande de débit {}, réconciliation à venir", p.getId(), uncertain);
        }
    }

    @Transactional(readOnly = true)
    public Payment get(String userId, UUID paymentId) {
        return payments.findByIdAndUserId(paymentId, userId)
                .orElseThrow(() -> BusinessError.notFound("Paiement"));
    }

    @Transactional(readOnly = true)
    public List<Payment> history(String userId, UUID requestId) {
        requests.findByIdAndUserId(requestId, userId).orElseThrow(() -> BusinessError.notFound("Demande"));
        return payments.findByRequestIdOrderByCreatedAtDesc(requestId);
    }

    private static String normalizeKey(String key) {
        if (key == null) {
            return null;
        }
        String c = key.trim();
        if (c.isEmpty() || c.length() > MAX_KEY_LENGTH) {
            throw new BusinessError(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "En-tête Idempotency-Key vide ou trop long (100 caractères max)");
        }
        return c;
    }
}
