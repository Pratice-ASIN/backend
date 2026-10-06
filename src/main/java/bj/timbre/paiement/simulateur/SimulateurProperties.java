package bj.timbre.paiement.simulateur;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "simulateur")
public record SimulateurProperties(boolean actif, Mode mode, Duration delaiResultat, String urlCallback) {

    public enum Mode {
        /** Le résultat est renvoyé automatiquement après {@code delaiResultat}. */
        AUTO,
        /** Le résultat est déclenché à la main (démo, tests). */
        MANUEL
    }
}
