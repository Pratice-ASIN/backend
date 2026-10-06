package bj.taxstamp.payment.operator;

import bj.taxstamp.payment.payment.MobileOperator;

/**
 * Port vers l'opérateur de mobile money. Le service de paiement ne dépend que de
 * cette interface ; l'implémentation actuelle est le simulateur, une implémentation
 * HTTP réelle par opérateur la remplacerait sans toucher au reste du code.
 */
public interface OperatorClient {

    /**
     * Demande un débit. L'opérateur ne fait qu'accuser réception : le résultat
     * arrive plus tard par un rappel signé.
     *
     * @param request la {@code reference} (identifiant de notre paiement) sert de clé
     *                d'idempotence chez l'opérateur.
     * @throws DebitRefuseException          refus immédiat et certain (aucun débit).
     * @throws OperateurIndisponibleException issue inconnue (délai dépassé, réponse perdue) :
     *                                        le débit a peut-être été enregistré.
     */
    Acknowledgement requestDebit(DebitRequest request);

    /** Interroge l'opérateur sur l'état d'un débit (canal sortant authentifié). */
    OperatorStatusQuery queryStatus(MobileOperator operator, String reference);

    /** Demande l'annulation d'un débit encore en attente ; renvoie l'état résultant. */
    OperatorStatusQuery cancel(MobileOperator operator, String reference);
}
