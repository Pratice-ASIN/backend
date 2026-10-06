package bj.taxstamp.payment.request.dto;

import java.time.Instant;
import java.util.UUID;

import bj.taxstamp.payment.request.DocumentRequest;
import bj.taxstamp.payment.request.DocumentRequestStatus;
import bj.taxstamp.payment.request.DocumentType;

public record DocumentRequestResponse(
        UUID id,
        DocumentType documentType,
        String documentLabel,
        int copies,
        long unitPrice,
        long serviceFee,
        long amountDue,
        String currency,
        DocumentRequestStatus status,
        Instant createdAt) {

    public static DocumentRequestResponse from(DocumentRequest d, DocumentRequestStatus status) {
        return new DocumentRequestResponse(
                d.getId(),
                d.getDocumentType(),
                d.getDocumentType().getLabel(),
                d.getCopies(),
                d.getDocumentType().getUnitPrice(),
                DocumentType.SERVICE_FEE,
                d.getAmount(),
                "XOF",
                status,
                d.getCreatedAt());
    }
}
