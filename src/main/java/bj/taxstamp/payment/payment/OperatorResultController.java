package bj.taxstamp.payment.payment;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Point d'entrée des rappels opérateur. Une URL par opérateur : le secret de
 * vérification est choisi d'après l'URL, avant même de lire le contenu.
 * Le corps est reçu brut (octets) car la signature porte sur les octets exacts.
 */
@RestController
public class OperatorResultController {

    public static final String SIGNATURE_HEADER = "X-Signature";

    private final OperatorResultService service;

    public OperatorResultController(OperatorResultService service) {
        this.service = service;
    }

    @PostMapping("/api/operators/{operator}/results")
    public OperatorResultService.ProcessingResult receive(
            @PathVariable MobileOperator operator,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody byte[] body) {
        return service.process(operator, body, signature);
    }
}
