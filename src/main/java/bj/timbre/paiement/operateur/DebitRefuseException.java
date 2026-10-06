package bj.timbre.paiement.operateur;

/** L'opérateur a refusé la demande de débit de façon certaine : rien n'a été prélevé. */
public class DebitRefuseException extends RuntimeException {

    public DebitRefuseException(String message) {
        super(message);
    }
}
