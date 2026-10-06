package bj.taxstamp.payment.payment;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(
        Duration statusCheckDelay,
        Duration expiryDelay,
        Map<MobileOperator, String> operatorSecrets) {

    public String secretFor(MobileOperator operator) {
        String secret = operatorSecrets == null ? null : operatorSecrets.get(operator);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Aucun secret configuré pour l'opérateur " + operator);
        }
        return secret;
    }
}
