package bj.timbre.paiement.demande.dto;

import bj.timbre.paiement.demande.TypeActe;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Pas de champ "montant" : il est toujours calculé par le service.
 * (Un champ inconnu envoyé par le client est rejeté, cf. application.yml.)
 */
public record CreerDemandeRequete(
        @NotNull(message = "obligatoire") TypeActe typeActe,
        @NotNull(message = "obligatoire")
        @Min(value = 1, message = "au moins 1 copie")
        @Max(value = 20, message = "20 copies au maximum")
        Integer nombreCopies) {
}
