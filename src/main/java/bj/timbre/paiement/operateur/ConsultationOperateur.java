package bj.timbre.paiement.operateur;

public record ConsultationOperateur(Etat etat, String referenceOperateur, String motif) {

    public enum Etat {
        /** Débit reçu, résultat pas encore connu. */
        EN_ATTENTE,
        SUCCES,
        ECHEC,
        /** Débit annulé avant d'être exécuté : aucun prélèvement. */
        ANNULE,
        /** L'opérateur n'a jamais reçu ce débit. */
        INCONNU
    }
}
