package bj.timbre.paiement.demande.dto;

import bj.timbre.paiement.demande.TypeActe;

public record TypeActeReponse(TypeActe code, String libelle, long tarifUnitaire, long fraisService, String devise) {

    public static TypeActeReponse de(TypeActe t) {
        return new TypeActeReponse(t, t.getLibelle(), t.getTarifUnitaire(), TypeActe.FRAIS_SERVICE, "XOF");
    }
}
