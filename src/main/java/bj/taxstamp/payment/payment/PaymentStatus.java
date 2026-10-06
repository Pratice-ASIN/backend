package bj.taxstamp.payment.payment;

/**
 * Cycle de vie d'un paiement :
 *
 * <pre>
 *   PENDING ──► SUCCEEDED   (résultat signé de l'opérateur, ou consultation)
 *      │
 *      ├──────► FAILED    (refus de l'opérateur)
 *      │
 *      └──────► EXPIRED    (aucun résultat : débit annulé chez l'opérateur)
 * </pre>
 *
 * Les trois états finaux sont définitifs : aucun message ultérieur ne les modifie.
 */
public enum PaymentStatus {

    PENDING("Paiement en cours : confirmez le débit sur votre téléphone"),
    SUCCEEDED("Paiement réussi"),
    FAILED("Paiement échoué : vous pouvez réessayer"),
    EXPIRED("Aucune réponse de l'opérateur, paiement annulé : vous pouvez réessayer");

    private final String message;

    PaymentStatus(String message) {
        this.message = message;
    }

    public boolean isFinal() {
        return this != PENDING;
    }

    public String getMessage() {
        return message;
    }
}
