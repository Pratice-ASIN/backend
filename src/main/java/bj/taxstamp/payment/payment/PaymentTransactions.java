package bj.taxstamp.payment.payment;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bj.taxstamp.payment.common.BusinessError;
import bj.taxstamp.payment.request.DocumentRequest;
import bj.taxstamp.payment.request.DocumentRequestRepository;

/**
 * Unités transactionnelles courtes du paiement.
 *
 * Volontairement séparées de {@link PaiementService} : l'appel à l'opérateur ne doit
 * JAMAIS se faire à l'intérieur d'une transaction. Le paiement est d'abord enregistré
 * et validé (commit), puis seul le fil d'exécution qui l'a créé demande le débit.
 */
@Service
public class PaymentTransactions {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransactions.class);

    private final PaymentRepository payments;
    private final DocumentRequestRepository requests;
    private final Clock clock;

    public PaymentTransactions(PaymentRepository payments, DocumentRequestRepository requests, Clock clock) {
        this.payments = payments;
        this.requests = requests;
        this.clock = clock;
    }

    public record PaymentCreation(Payment payment, boolean created) {
    }

    /**
     * Crée le paiement PENDING si la demande est payable.
     * En cas de course avec une requête identique, la contrainte unique fait échouer
     * l'insertion perdante ({@code DataIntegrityViolationException}) : voir
     * {@link #resolveAfterConflict}.
     */
    @Transactional
    public PaymentCreation create(String userId, UUID requestId, String phoneNumber, MobileOperator operator,
                                  String idempotencyKey) {
        DocumentRequest request = requests.findByIdAndUserId(requestId, userId)
                .orElseThrow(() -> BusinessError.notFound("Demande"));

        if (idempotencyKey != null) {
            Optional<Payment> replay = payments.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (replay.isPresent()) {
                return new PaymentCreation(checkReplay(replay.get(), requestId, phoneNumber, operator), false);
            }
        }

        Optional<Payment> active = payments.findByRequestLock(requestId);
        if (active.isPresent()) {
            return new PaymentCreation(replayOrConflict(active.get(), userId, requestId, phoneNumber, operator,
                    idempotencyKey), false);
        }

        Payment payment = Payment.create(request, userId, phoneNumber, operator, idempotencyKey, clock.instant());
        // flush immédiat : la violation de contrainte éventuelle survient ici, pas au commit.
        return new PaymentCreation(payments.saveAndFlush(payment), true);
    }

    /**
     * Appelé après une insertion perdue face à une requête concurrente (nouvelle transaction).
     *
     * @return le paiement gagnant si la requête est un rejeu (même clé) ; vide si le
     *         gagnant n'est pas encore visible (transaction concurrente pas encore validée).
     * @throws ErreurMetier 409 si un autre paiement actif occupe la demande.
     */
    @Transactional(readOnly = true)
    public Optional<Payment> resolveAfterConflict(String userId, UUID requestId, String phoneNumber,
                                                  MobileOperator operator, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<Payment> replay = payments.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (replay.isPresent()) {
                return Optional.of(checkReplay(replay.get(), requestId, phoneNumber, operator));
            }
        }
        return payments.findByRequestLock(requestId)
                .map(active -> replayOrConflict(active, userId, requestId, phoneNumber, operator, idempotencyKey));
    }

    @Transactional
    public void recordAcknowledgement(UUID paymentId, String operatorReference) {
        payments.findForUpdate(paymentId)
                .ifPresent(p -> p.recordOperatorReference(operatorReference, clock.instant()));
    }

    /**
     * Applique un résultat final sous verrou ligne.
     *
     * @return le paiement mis à jour, ou vide s'il était déjà finalisé (résultat ignoré).
     */
    @Transactional
    public Optional<Payment> applyResult(UUID paymentId, PaymentStatus status, String operatorReference,
                                                String reason) {
        Payment p = payments.findForUpdate(paymentId)
                .orElseThrow(() -> BusinessError.notFound("Paiement"));
        if (p.getStatus().isFinal()) {
            if (p.getStatus() != status) {
                log.warn("Résultat contradictoire ignoré pour le paiement {} : déjà {}, reçu {}",
                        paymentId, p.getStatus(), status);
            } else {
                log.info("Résultat en double ignoré pour le paiement {} ({})", paymentId, status);
            }
            return Optional.empty();
        }
        p.complete(status, operatorReference, reason, clock.instant());
        log.info("Paiement {} finalisé : {}", paymentId, status);
        return Optional.of(p);
    }

    @Transactional(readOnly = true)
    public Payment reload(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> BusinessError.notFound("Paiement"));
    }

    /**
     * Le paiement actif peut être celui d'une requête identique validée entre la
     * recherche par clé d'idempotence et celle-ci : c'est alors un rejeu, pas un conflit.
     */
    private static Payment replayOrConflict(Payment active, String userId, UUID requestId, String phoneNumber,
                                            MobileOperator operator, String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKey.equals(active.getIdempotencyKey())
                && userId.equals(active.getUserId())) {
            return checkReplay(active, requestId, phoneNumber, operator);
        }
        throw conflict(active);
    }

    private static Payment checkReplay(Payment existing, UUID requestId, String phoneNumber, MobileOperator operator) {
        boolean sameRequest = existing.getRequestId().equals(requestId)
                && existing.getPhoneNumber().equals(phoneNumber)
                && existing.getOperator() == operator;
        if (!sameRequest) {
            throw new BusinessError(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "Cette clé d'idempotence a déjà servi pour une autre requête de paiement");
        }
        return existing;
    }

    private static BusinessError conflict(Payment active) {
        Map<String, Object> details = Map.of("paymentId", active.getId(), "status", active.getStatus());
        if (active.getStatus() == PaymentStatus.SUCCEEDED) {
            return new BusinessError(HttpStatus.CONFLICT, "REQUEST_ALREADY_PAID",
                    "Cette demande est déjà payée", details);
        }
        return new BusinessError(HttpStatus.CONFLICT, "PAYMENT_PENDING",
                "Un paiement est déjà en cours pour cette demande", details);
    }
}
