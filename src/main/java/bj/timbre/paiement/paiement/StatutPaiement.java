package bj.timbre.paiement.paiement;

/**
 * Cycle de vie d'un paiement :
 *
 * <pre>
 *   EN_COURS ──► REUSSI   (résultat signé de l'opérateur, ou consultation)
 *      │
 *      ├──────► ECHOUE    (refus de l'opérateur)
 *      │
 *      └──────► EXPIRE    (aucun résultat : débit annulé chez l'opérateur)
 * </pre>
 *
 * Les trois états finaux sont définitifs : aucun message ultérieur ne les modifie.
 */
public enum StatutPaiement {

    EN_COURS("Paiement en cours : confirmez le débit sur votre téléphone"),
    REUSSI("Paiement réussi"),
    ECHOUE("Paiement échoué : vous pouvez réessayer"),
    EXPIRE("Aucune réponse de l'opérateur, paiement annulé : vous pouvez réessayer");

    private final String message;

    StatutPaiement(String message) {
        this.message = message;
    }

    public boolean estFinal() {
        return this != EN_COURS;
    }

    public String getMessage() {
        return message;
    }
}
