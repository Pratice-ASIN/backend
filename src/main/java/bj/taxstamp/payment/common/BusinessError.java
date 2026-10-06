package bj.taxstamp.payment.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Erreur fonctionnelle renvoyée à l'appelant avec un code stable (exploitable par
 * l'application mobile) et un statut HTTP.
 */
public class BusinessError extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public BusinessError(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public BusinessError(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static BusinessError notFound(String resource) {
        // 404 aussi quand la ressource appartient à un autre usager :
        // on ne révèle pas son existence.
        return new BusinessError(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", resource + " introuvable");
    }

    public static BusinessError unknownDocumentType(String code) {
        return new BusinessError(HttpStatus.BAD_REQUEST, "UNKNOWN_DOCUMENT_TYPE",
                "Type d'acte inconnu : " + code);
    }

    public static BusinessError userNotIdentified() {
        return new BusinessError(HttpStatus.UNAUTHORIZED, "USER_NOT_IDENTIFIED",
                "En-tête X-User-Id absent ou invalide");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
