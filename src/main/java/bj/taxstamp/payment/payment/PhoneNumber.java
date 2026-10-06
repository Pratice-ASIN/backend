package bj.taxstamp.payment.payment;

import java.util.regex.Pattern;

/** Règle : 10 chiffres commençant par 01 (format des numéros mobiles au Bénin). */
public final class PhoneNumber {

    private static final Pattern FORMAT = Pattern.compile("01\\d{8}");

    private PhoneNumber() {
    }

    public static boolean isValid(String number) {
        return number != null && FORMAT.matcher(number).matches();
    }

    /** Pour l'affichage : 0197123456 → 01****3456. */
    public static String mask(String number) {
        if (number == null || number.length() < 6) {
            return number;
        }
        return number.substring(0, 2) + "****" + number.substring(number.length() - 4);
    }
}
