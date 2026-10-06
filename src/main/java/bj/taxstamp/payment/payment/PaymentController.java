package bj.taxstamp.payment.payment;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bj.taxstamp.payment.common.CurrentUser;
import bj.taxstamp.payment.payment.dto.StartPaymentRequest;
import bj.taxstamp.payment.payment.dto.PaymentResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class PaymentController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * 202 : nouveau paiement, débit demandé, résultat à suivre via GET /api/payments/{id}.
     * 200 : rejeu d'une requête déjà reçue (même Idempotency-Key) : aucun nouveau débit.
     */
    @PostMapping("/document-requests/{requestId}/payments")
    public ResponseEntity<PaymentResponse> start(CurrentUser user,
                                                 @PathVariable UUID requestId,
                                                 @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                 @Valid @RequestBody StartPaymentRequest payload) {
        PaymentService.StartResult l = service.start(user.id(), requestId, payload.phoneNumber(),
                payload.operator(), key);
        HttpStatus status = l.created() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(PaymentResponse.from(l.payment()));
    }

    @GetMapping("/document-requests/{requestId}/payments")
    public List<PaymentResponse> history(CurrentUser user, @PathVariable UUID requestId) {
        return service.history(user.id(), requestId).stream().map(PaymentResponse::from).toList();
    }

    @GetMapping("/payments/{paymentId}")
    public PaymentResponse get(CurrentUser user, @PathVariable UUID paymentId) {
        return PaymentResponse.from(service.get(user.id(), paymentId));
    }
}
