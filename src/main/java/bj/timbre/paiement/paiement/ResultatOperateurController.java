package bj.timbre.paiement.paiement;

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
public class ResultatOperateurController {

    public static final String ENTETE_SIGNATURE = "X-Signature";

    private final ResultatOperateurService service;

    public ResultatOperateurController(ResultatOperateurService service) {
        this.service = service;
    }

    @PostMapping("/api/operateurs/{operateur}/resultats")
    public ResultatOperateurService.Traitement recevoir(
            @PathVariable Operateur operateur,
            @RequestHeader(value = ENTETE_SIGNATURE, required = false) String signature,
            @RequestBody byte[] corps) {
        return service.traiter(operateur, corps, signature);
    }
}
