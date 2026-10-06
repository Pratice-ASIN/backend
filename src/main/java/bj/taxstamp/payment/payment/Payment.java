package bj.taxstamp.payment.payment;

import java.time.Instant;
import java.util.UUID;

import bj.taxstamp.payment.request.DocumentRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Une tentative de paiement d'une demande d'acte.
 *
 * <h2>Garantie "un seul paiement actif par demande"</h2>
 * La colonne {@code request_lock} vaut l'identifiant de la demande tant que le
 * paiement est PENDING ou SUCCEEDED, et NULL après un échec ou une expiration.
 * Elle porte une contrainte UNIQUE : la base de données refuse donc, même sous
 * forte concurrence, un deuxième paiement en cours ou réussi pour la même demande
 * (les NULL ne sont pas comparés entre eux, ce qui autorise les nouvelles tentatives
 * après un échec). C'est l'équivalent portable d'un index unique partiel.
 */
@Entity
@Table(name = "payment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_request_lock", columnNames = "request_lock"),
                @UniqueConstraint(name = "uk_payment_idempotency_key", columnNames = {"user_id", "idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_payment_request", columnList = "request_id"),
                @Index(name = "idx_payment_status_created", columnList = "status, created_at")
        })
public class Payment {

    @Id
    private UUID id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "phone_number", nullable = false, length = 10)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false, length = 10)
    private MobileOperator operator;

    /** Copié depuis la demande au lancement : jamais fourni par l'usager. */
    @Column(name = "amount", nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private PaymentStatus status;

    @Column(name = "request_lock")
    private UUID requestLock;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "operator_reference", length = 100)
    private String operatorReference;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Payment() {
    }

    public static Payment create(DocumentRequest request, String userId, String phoneNumber, MobileOperator operator,
                                 String idempotencyKey, Instant now) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.requestId = request.getId();
        p.userId = userId;
        p.phoneNumber = phoneNumber;
        p.operator = operator;
        p.amount = request.getAmount();
        p.status = PaymentStatus.PENDING;
        p.requestLock = request.getId();
        p.idempotencyKey = idempotencyKey;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    /** Passage dans un état final. Interdit si le paiement est déjà finalisé. */
    public void complete(PaymentStatus newStatus, String operatorReference, String reason, Instant now) {
        if (status.isFinal()) {
            throw new IllegalStateException("Paiement " + id + " déjà finalisé (" + status + ")");
        }
        if (!newStatus.isFinal()) {
            throw new IllegalArgumentException("Statut final attendu : " + newStatus);
        }
        this.status = newStatus;
        this.reason = reason;
        if (this.operatorReference == null) {
            this.operatorReference = operatorReference;
        }
        // Après un échec, la demande redevient payable ; après une réussite, elle reste verrouillée.
        if (newStatus != PaymentStatus.SUCCEEDED) {
            this.requestLock = null;
        }
        this.updatedAt = now;
    }

    public void recordOperatorReference(String reference, Instant now) {
        if (this.operatorReference == null && reference != null) {
            this.operatorReference = reference;
            this.updatedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getUserId() {
        return userId;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public MobileOperator getOperator() {
        return operator;
    }

    public long getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getOperatorReference() {
        return operatorReference;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
