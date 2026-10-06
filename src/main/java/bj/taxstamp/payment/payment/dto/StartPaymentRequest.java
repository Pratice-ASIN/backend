package bj.taxstamp.payment.payment.dto;

import bj.taxstamp.payment.payment.MobileOperator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Aucun montant ici : le service le reprend de la demande. */
public record StartPaymentRequest(
        @NotBlank(message = "obligatoire") String phoneNumber,
        @NotNull(message = "obligatoire (MTN, MOOV ou CELTIIS)") MobileOperator operator) {
}
