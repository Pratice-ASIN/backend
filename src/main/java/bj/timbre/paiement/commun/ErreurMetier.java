package bj.timbre.paiement.commun;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Erreur fonctionnelle renvoyée à l'appelant avec un code stable (exploitable par
 * l'application mobile) et un statut HTTP.
 */
public class ErreurMetier extends RuntimeException {

    private final HttpStatus statut;
    private final String code;
    private final Map<String, Object> details;

    public ErreurMetier(HttpStatus statut, String code, String message) {
        this(statut, code, message, Map.of());
    }

    public ErreurMetier(HttpStatus statut, String code, String message, Map<String, Object> details) {
        super(message);
        this.statut = statut;
        this.code = code;
        this.details = details;
    }

    public static ErreurMetier introuvable(String ressource) {
        // 404 aussi quand la ressource appartient à un autre usager :
        // on ne révèle pas son existence.
        return new ErreurMetier(HttpStatus.NOT_FOUND, "RESSOURCE_INTROUVABLE", ressource + " introuvable");
    }

    public static ErreurMetier usagerNonIdentifie() {
        return new ErreurMetier(HttpStatus.UNAUTHORIZED, "USAGER_NON_IDENTIFIE",
                "En-tête X-Usager-Id absent ou invalide");
    }

    public HttpStatus getStatut() {
        return statut;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
