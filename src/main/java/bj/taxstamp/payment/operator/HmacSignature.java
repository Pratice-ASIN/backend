package bj.taxstamp.payment.operator;

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
public final class HmacSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacSignature() {
    }

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    /** Comparaison en temps constant : ne divulgue pas, par le temps de réponse, le début de la signature attendue. */
    public static boolean verify(String secret, byte[] body, String receivedSignature) {
        if (receivedSignature == null || receivedSignature.isBlank() || body == null) {
            return false;
        }
        byte[] expected = sign(secret, body).getBytes(StandardCharsets.US_ASCII);
        byte[] received = receivedSignature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, received);
    }
}
