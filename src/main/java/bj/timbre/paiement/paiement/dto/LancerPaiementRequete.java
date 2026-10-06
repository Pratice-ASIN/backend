package bj.timbre.paiement.paiement.dto;

import bj.timbre.paiement.paiement.Operateur;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Aucun montant ici : le service le reprend de la demande. */
public record LancerPaiementRequete(
        @NotBlank(message = "obligatoire") String telephone,
        @NotNull(message = "obligatoire (MTN, MOOV ou CELTIIS)") Operateur operateur) {
}
