package bj.taxstamp.payment.request;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bj.taxstamp.payment.common.BusinessError;
import bj.taxstamp.payment.payment.PaymentRepository;
import bj.taxstamp.payment.payment.PaymentStatus;

@Service
public class DocumentRequestService {

    private final DocumentRequestRepository requests;
    private final DocumentTypeRepository documentTypes;
    private final PaymentRepository payments;
    private final Clock clock;

    public DocumentRequestService(DocumentRequestRepository requests, DocumentTypeRepository documentTypes,
            PaymentRepository payments, Clock clock) {
        this.requests = requests;
        this.documentTypes = documentTypes;
        this.payments = payments;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DocumentType> documentTypes() {
        return documentTypes.findAllByOrderByLabelAsc();
    }

    @Transactional
    public DocumentRequest create(String userId, String documentTypeCode, int copies) {
        DocumentType documentType = documentTypes.findById(documentTypeCode)
                .orElseThrow(() -> BusinessError.unknownDocumentType(documentTypeCode));
        return requests.save(DocumentRequest.create(userId, documentType, copies, clock.instant()));
    }

    @Transactional(readOnly = true)
    public DocumentRequest get(String userId, UUID requestId) {
        return requests.findByIdAndUserId(requestId, userId)
                .orElseThrow(() -> BusinessError.notFound("Demande"));
    }

    @Transactional(readOnly = true)
    public List<DocumentRequest> list(String userId) {
        return requests.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** Déduit du seul paiement actif (PENDING ou SUCCEEDED) de la demande, s'il existe. */
    @Transactional(readOnly = true)
    public DocumentRequestStatus status(UUID requestId) {
        return payments.findByRequestLock(requestId)
                .map(p -> p.getStatus() == PaymentStatus.SUCCEEDED ? DocumentRequestStatus.PAID : DocumentRequestStatus.PAYMENT_PENDING)
                .orElse(DocumentRequestStatus.UNPAID);
    }
}
