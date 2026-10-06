package bj.taxstamp.payment.request.dto;

import bj.taxstamp.payment.request.DocumentType;

public record DocumentTypeResponse(String code, String label, long unitPrice, long serviceFee, String currency) {

    public static DocumentTypeResponse from(DocumentType t) {
        return new DocumentTypeResponse(t.getCode(), t.getLabel(), t.getUnitPrice(), DocumentType.SERVICE_FEE, "XOF");
    }
}
