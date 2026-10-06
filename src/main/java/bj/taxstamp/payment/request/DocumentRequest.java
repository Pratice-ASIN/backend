package bj.taxstamp.payment.request;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "document_request", indexes = @Index(name = "idx_document_request_user", columnList = "user_id"))
public class DocumentRequest {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 40)
    private DocumentType documentType;

    @Column(name = "copies", nullable = false)
    private int copies;

    /** Montant figé à la création, calculé par le service. */
    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected DocumentRequest() {
    }

    public static DocumentRequest create(String userId, DocumentType documentType, int copies, Instant now) {
        DocumentRequest d = new DocumentRequest();
        d.id = UUID.randomUUID();
        d.userId = userId;
        d.documentType = documentType;
        d.copies = copies;
        d.amount = documentType.amountDue(copies);
        d.createdAt = now;
        return d;
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public int getCopies() {
        return copies;
    }

    public long getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
