package bj.taxstamp.payment.payment.dto;

import java.time.Instant;
import java.util.UUID;

import bj.taxstamp.payment.payment.MobileOperator;
import bj.taxstamp.payment.payment.Payment;
import bj.taxstamp.payment.payment.PaymentStatus;
import bj.taxstamp.payment.payment.PhoneNumber;

public record PaymentResponse(
        UUID id,
        UUID requestId,
        MobileOperator operator,
        String phoneNumber,
        long amount,
        String currency,
        PaymentStatus status,
        String message,
        String reason,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getRequestId(),
                p.getOperator(),
                PhoneNumber.mask(p.getPhoneNumber()),
                p.getAmount(),
                "XOF",
                p.getStatus(),
                p.getStatus().getMessage(),
                p.getReason(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
