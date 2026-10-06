package bj.timbre.paiement.operateur;

import bj.timbre.paiement.paiement.Operateur;

/**
 * Message de rappel envoyé par l'opérateur (corps JSON, signé dans l'en-tête X-Signature).
 *
 * @param reference          identifiant de notre paiement, transmis lors de la demande de débit
 * @param referenceOperateur identifiant de la transaction chez l'opérateur
 * @param statut             SUCCES ou ECHEC
 * @param montant            montant effectivement traité par l'opérateur
 */
public record MessageResultatOperateur(
        String reference,
        String referenceOperateur,
        Operateur operateur,
        String statut,
        Long montant,
        String motif) {

    public static final String SUCCES = "SUCCES";
    public static final String ECHEC = "ECHEC";
}
