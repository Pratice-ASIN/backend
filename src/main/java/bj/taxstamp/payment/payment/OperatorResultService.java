package bj.taxstamp.payment.payment;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import bj.taxstamp.payment.common.BusinessError;
import bj.taxstamp.payment.operator.OperatorResultMessage;
import bj.taxstamp.payment.operator.HmacSignature;

/**
 * Prise en compte des résultats transmis par les opérateurs.
 *
 * Ordre des contrôles :
 * <ol>
 *   <li>signature HMAC vérifiée AVANT toute lecture du contenu ;</li>
 *   <li>le paiement existe et appartient bien à cet opérateur ;</li>
 *   <li>le montant annoncé est celui du paiement (sinon rejet, rien n'est modifié) ;</li>
 *   <li>transition PENDING → état final sous verrou ligne ; un paiement déjà finalisé
 *       n'est jamais modifié (doublons et rejeux sans effet).</li>
 * </ol>
 */
@Service
public class OperatorResultService {

    private static final Logger log = LoggerFactory.getLogger(OperatorResultService.class);

    private final PaymentRepository payments;
    private final PaymentTransactions transactions;
    private final PaymentProperties properties;
    private final ObjectReader reader;

    public OperatorResultService(PaymentRepository payments, PaymentTransactions transactions,
                                 PaymentProperties properties, ObjectMapper objectMapper) {
        this.payments = payments;
        this.transactions = transactions;
        this.properties = properties;
        // Tolérant aux champs ajoutés par l'opérateur (le message est déjà authentifié à ce stade).
        this.reader = objectMapper.readerFor(OperatorResultMessage.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public record ProcessingResult(UUID paymentId, PaymentStatus status, boolean applied) {
    }

    public ProcessingResult process(MobileOperator operator, byte[] body, String signature) {
        if (!HmacSignature.verify(properties.secretFor(operator), body, signature)) {
            log.warn("Résultat {} rejeté : signature absente ou invalide", operator);
            throw new BusinessError(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Signature invalide");
        }

        OperatorResultMessage message = read(body);
        Payment payment = find(message.reference())
                .filter(p -> p.getOperator() == operator)
                .orElseThrow(() -> BusinessError.notFound("Paiement"));

        if (message.operator() != null && message.operator() != operator) {
            throw new BusinessError(HttpStatus.UNPROCESSABLE_ENTITY, "OPERATOR_MISMATCH",
                    "L'opérateur du message ne correspond pas à l'émetteur");
        }
        if (message.amount() == null || message.amount() != payment.getAmount()) {
            log.error("ANOMALIE paiement {} : montant annoncé {} ≠ montant dû {}. Résultat non appliqué.",
                    payment.getId(), message.amount(), payment.getAmount());
            throw new BusinessError(HttpStatus.UNPROCESSABLE_ENTITY, "AMOUNT_MISMATCH",
                    "Le montant annoncé ne correspond pas au montant du paiement");
        }

        PaymentStatus status = switch (String.valueOf(message.status())) {
            case OperatorResultMessage.SUCCESS -> PaymentStatus.SUCCEEDED;
            case OperatorResultMessage.FAILURE -> PaymentStatus.FAILED;
            default -> throw new BusinessError(HttpStatus.BAD_REQUEST, "UNKNOWN_STATUS",
                    "Statut attendu : SUCCESS ou FAILURE");
        };

        Optional<Payment> updated = transactions.applyResult(payment.getId(), status,
                message.operatorReference(), status == PaymentStatus.FAILED ? message.reason() : null);
        if (updated.isPresent()) {
            return new ProcessingResult(payment.getId(), updated.get().getStatus(), true);
        }
        // Déjà finalisé : on acquitte quand même (200) pour que l'opérateur cesse ses relances.
        return new ProcessingResult(payment.getId(), transactions.reload(payment.getId()).getStatus(), false);
    }

    private OperatorResultMessage read(byte[] body) {
        try {
            return reader.readValue(body);
        } catch (IOException e) {
            throw new BusinessError(HttpStatus.BAD_REQUEST, "UNREADABLE_MESSAGE", "Message de l'opérateur illisible");
        }
    }

    private Optional<Payment> find(String reference) {
        try {
            return reference == null ? Optional.empty() : payments.findById(UUID.fromString(reference));
        } catch (IllegalArgumentException malformedReference) {
            return Optional.empty();
        }
    }
}
