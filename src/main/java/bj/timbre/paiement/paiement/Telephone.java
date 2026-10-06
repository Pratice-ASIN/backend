package bj.timbre.paiement.paiement;

import java.util.regex.Pattern;

/** Règle : 10 chiffres commençant par 01 (format des numéros mobiles au Bénin). */
public final class Telephone {

    private static final Pattern FORMAT = Pattern.compile("01\\d{8}");

    private Telephone() {
    }

    public static boolean estValide(String numero) {
        return numero != null && FORMAT.matcher(numero).matches();
    }

    /** Pour l'affichage : 0197123456 → 01****3456. */
    public static String masquer(String numero) {
        if (numero == null || numero.length() < 6) {
            return numero;
        }
        return numero.substring(0, 2) + "****" + numero.substring(numero.length() - 4);
    }
}
