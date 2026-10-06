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
    private final PaymentRepository payments;
    private final Clock clock;

    public DocumentRequestService(DocumentRequestRepository requests, PaymentRepository payments, Clock clock) {
        this.requests = requests;
        this.payments = payments;
        this.clock = clock;
    }

    @Transactional
    public DocumentRequest create(String userId, DocumentType documentType, int copies) {
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
