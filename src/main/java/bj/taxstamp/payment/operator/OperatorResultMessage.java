package bj.taxstamp.payment.operator;

import bj.taxstamp.payment.payment.MobileOperator;

/**
 * Message de rappel envoyé par l'opérateur (corps JSON, signé dans l'en-tête X-Signature).
 *
 * @param reference          identifiant de notre paiement, transmis lors de la demande de débit
 * @param operatorReference identifiant de la transaction chez l'opérateur
 * @param status             SUCCESS ou FAILURE
 * @param amount            montant effectivement traité par l'opérateur
 */
public record OperatorResultMessage(
        String reference,
        String operatorReference,
        MobileOperator operator,
        String status,
        Long amount,
        String reason) {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";
}
