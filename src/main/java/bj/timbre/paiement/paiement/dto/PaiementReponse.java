package bj.timbre.paiement.paiement.dto;

import java.time.Instant;
import java.util.UUID;

import bj.timbre.paiement.paiement.Operateur;
import bj.timbre.paiement.paiement.Paiement;
import bj.timbre.paiement.paiement.StatutPaiement;
import bj.timbre.paiement.paiement.Telephone;

public record PaiementReponse(
        UUID id,
        UUID demandeId,
        Operateur operateur,
        String telephone,
        long montant,
        String devise,
        StatutPaiement statut,
        String message,
        String motif,
        Instant creeLe,
        Instant majLe) {

    public static PaiementReponse de(Paiement p) {
        return new PaiementReponse(
                p.getId(),
                p.getDemandeId(),
                p.getOperateur(),
                Telephone.masquer(p.getTelephone()),
                p.getMontant(),
                "XOF",
                p.getStatut(),
                p.getStatut().getMessage(),
                p.getMotif(),
                p.getCreeLe(),
                p.getMajLe());
    }
}
