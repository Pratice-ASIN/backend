package bj.timbre.paiement.simulateur;

import java.util.Collection;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bj.timbre.paiement.commun.ErreurMetier;

/** Pilotage du simulateur pour la démonstration. N'existe pas en production (simulateur.actif=false). */
@RestController
@RequestMapping("/simulateur/transactions")
@ConditionalOnProperty(name = "simulateur.actif", havingValue = "true")
public class SimulateurController {

    private final SimulateurOperateur simulateur;

    public SimulateurController(SimulateurOperateur simulateur) {
        this.simulateur = simulateur;
    }

    public record ResultatManuel(boolean succes, String motif) {
    }

    @GetMapping
    public Collection<Map<String, Object>> lister() {
        return simulateur.lister();
    }

    @PostMapping("/{reference}/resultat")
    public Map<String, Object> transmettre(@PathVariable String reference, @RequestBody ResultatManuel resultat) {
        if (!simulateur.transmettreResultat(reference, resultat.succes(), resultat.motif())) {
            throw new ErreurMetier(HttpStatus.CONFLICT, "TRANSACTION_NON_EN_ATTENTE",
                    "Transaction inconnue ou déjà terminée");
        }
        return Map.of("reference", reference, "envoye", true);
    }

    @PostMapping("/{reference}/renvoyer")
    public Map<String, Object> renvoyer(@PathVariable String reference) {
        if (!simulateur.renvoyerResultat(reference)) {
            throw new ErreurMetier(HttpStatus.CONFLICT, "AUCUN_RESULTAT", "Aucun résultat à renvoyer");
        }
        return Map.of("reference", reference, "renvoye", true);
    }
}
