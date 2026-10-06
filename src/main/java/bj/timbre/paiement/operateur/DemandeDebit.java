package bj.timbre.paiement.operateur;

import bj.timbre.paiement.paiement.Operateur;

public record DemandeDebit(Operateur operateur, String reference, String telephone, long montant) {
}
