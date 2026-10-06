package bj.timbre.paiement.paiement;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paiement")
public record PaiementProperties(
        Duration delaiConsultation,
        Duration delaiExpiration,
        Map<Operateur, String> secretsOperateurs) {

    public String secretDe(Operateur operateur) {
        String secret = secretsOperateurs == null ? null : secretsOperateurs.get(operateur);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Aucun secret configuré pour l'opérateur " + operateur);
        }
        return secret;
    }
}
