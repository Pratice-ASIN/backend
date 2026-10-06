package bj.timbre.paiement.demande.dto;

import java.time.Instant;
import java.util.UUID;

import bj.timbre.paiement.demande.DemandeActe;
import bj.timbre.paiement.demande.TypeActe;

public record DemandeReponse(
        UUID id,
        TypeActe typeActe,
        String libelleActe,
        int nombreCopies,
        long tarifUnitaire,
        long fraisService,
        long montantAPayer,
        String devise,
        Instant creeLe) {

    public static DemandeReponse de(DemandeActe d) {
        return new DemandeReponse(
                d.getId(),
                d.getTypeActe(),
                d.getTypeActe().getLibelle(),
                d.getNombreCopies(),
                d.getTypeActe().getTarifUnitaire(),
                TypeActe.FRAIS_SERVICE,
                d.getMontant(),
                "XOF",
                d.getCreeLe());
    }
}
