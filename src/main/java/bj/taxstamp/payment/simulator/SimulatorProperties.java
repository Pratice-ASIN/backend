package bj.taxstamp.payment.simulator;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(boolean enabled, Mode mode, Duration resultDelay, String callbackUrl) {

    public enum Mode {
        /** Le résultat est renvoyé automatiquement après {@code resultDelay}. */
        AUTO,
        /** Le résultat est déclenché à la main (démo, tests). */
        MANUAL
    }
}
