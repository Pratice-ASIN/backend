package bj.taxstamp.payment.operator;

public record OperatorStatusQuery(State state, String operatorReference, String reason) {

    public enum State {
        /** Débit reçu, résultat pas encore connu. */
        PENDING,
        SUCCESS,
        FAILURE,
        /** Débit annulé avant d'être exécuté : aucun prélèvement. */
        CANCELLED,
        /** L'opérateur n'a jamais reçu ce débit. */
        UNKNOWN
    }
}
