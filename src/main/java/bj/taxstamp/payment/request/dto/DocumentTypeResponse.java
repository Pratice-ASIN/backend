package bj.taxstamp.payment.request.dto;

import bj.taxstamp.payment.request.DocumentType;

public record DocumentTypeResponse(DocumentType code, String label, long unitPrice, long serviceFee, String currency) {

    public static DocumentTypeResponse from(DocumentType t) {
        return new DocumentTypeResponse(t, t.getLabel(), t.getUnitPrice(), DocumentType.SERVICE_FEE, "XOF");
    }
}
