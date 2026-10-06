package bj.timbre.paiement.paiement;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bj.timbre.paiement.commun.UsagerCourant;
import bj.timbre.paiement.paiement.dto.LancerPaiementRequete;
import bj.timbre.paiement.paiement.dto.PaiementReponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class PaiementController {

    public static final String ENTETE_IDEMPOTENCE = "Idempotency-Key";

    private final PaiementService service;

    public PaiementController(PaiementService service) {
        this.service = service;
    }

    /**
     * 202 : nouveau paiement, débit demandé, résultat à suivre via GET /api/paiements/{id}.
     * 200 : rejeu d'une requête déjà reçue (même Idempotency-Key) : aucun nouveau débit.
     */
    @PostMapping("/demandes/{demandeId}/paiements")
    public ResponseEntity<PaiementReponse> lancer(UsagerCourant usager,
                                                  @PathVariable UUID demandeId,
                                                  @RequestHeader(value = ENTETE_IDEMPOTENCE, required = false) String cle,
                                                  @Valid @RequestBody LancerPaiementRequete requete) {
        PaiementService.Lancement l = service.lancer(usager.id(), demandeId, requete.telephone(),
                requete.operateur(), cle);
        HttpStatus statut = l.nouveau() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(statut).body(PaiementReponse.de(l.paiement()));
    }

    @GetMapping("/demandes/{demandeId}/paiements")
    public List<PaiementReponse> historique(UsagerCourant usager, @PathVariable UUID demandeId) {
        return service.historique(usager.id(), demandeId).stream().map(PaiementReponse::de).toList();
    }

    @GetMapping("/paiements/{paiementId}")
    public PaiementReponse consulter(UsagerCourant usager, @PathVariable UUID paiementId) {
        return PaiementReponse.de(service.consulter(usager.id(), paiementId));
    }
}
