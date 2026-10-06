package bj.timbre.paiement.operateur;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signature HMAC-SHA256 (hexadécimal) calculée sur les octets bruts du corps du message,
 * avec un secret partagé propre à chaque opérateur.
 */
public final class SignatureHmac {

    private static final String ALGORITHME = "HmacSHA256";

    private SignatureHmac() {
    }

    public static String signer(String secret, byte[] corps) {
        try {
            Mac mac = Mac.getInstance(ALGORITHME);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHME));
            return HexFormat.of().formatHex(mac.doFinal(corps));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    /** Comparaison en temps constant : ne divulgue pas, par le temps de réponse, le début de la signature attendue. */
    public static boolean verifier(String secret, byte[] corps, String signatureRecue) {
        if (signatureRecue == null || signatureRecue.isBlank() || corps == null) {
            return false;
        }
        byte[] attendue = signer(secret, corps).getBytes(StandardCharsets.US_ASCII);
        byte[] recue = signatureRecue.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(attendue, recue);
    }
}
