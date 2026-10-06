package bj.taxstamp.payment.operator;

/**
 * Échange avec l'opérateur sans issue connue (délai dépassé, réponse perdue).
 * Le débit a PEUT-ÊTRE été enregistré : on ne conclut jamais à un échec sur cette
 * seule base, la réconciliation tranchera en interrogeant l'opérateur.
 */
public class OperatorUnavailableException extends RuntimeException {

    public OperatorUnavailableException(String message) {
        super(message);
    }
}
