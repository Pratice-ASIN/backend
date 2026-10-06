package bj.taxstamp.payment.operator;

/** L'opérateur a refusé la demande de débit de façon certaine : rien n'a été prélevé. */
public class DebitRejectedException extends RuntimeException {

    public DebitRejectedException(String message) {
        super(message);
    }
}
