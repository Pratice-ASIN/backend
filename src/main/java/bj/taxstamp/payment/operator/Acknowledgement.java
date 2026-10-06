package bj.taxstamp.payment.operator;

/** Accusé de réception d'une demande de débit : ce n'est PAS un résultat. */
public record Acknowledgement(String operatorReference) {
}
