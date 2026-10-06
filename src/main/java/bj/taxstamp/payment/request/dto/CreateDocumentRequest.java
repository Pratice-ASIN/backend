package bj.taxstamp.payment.request.dto;

import bj.taxstamp.payment.request.DocumentType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Pas de champ "montant" : il est toujours calculé par le service.
 * (Un champ inconnu envoyé par le client est rejeté, cf. application.yml.)
 */
public record CreateDocumentRequest(
        @NotNull(message = "obligatoire") DocumentType documentType,
        @NotNull(message = "obligatoire")
        @Min(value = 1, message = "au moins 1 copie")
        @Max(value = 20, message = "20 copies au maximum")
        Integer copies) {
}
