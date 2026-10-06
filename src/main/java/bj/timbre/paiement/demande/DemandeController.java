package bj.timbre.paiement.demande;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bj.timbre.paiement.commun.UsagerCourant;
import bj.timbre.paiement.demande.dto.CreerDemandeRequete;
import bj.timbre.paiement.demande.dto.DemandeReponse;
import bj.timbre.paiement.demande.dto.TypeActeReponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class DemandeController {

    private final DemandeService service;

    public DemandeController(DemandeService service) {
        this.service = service;
    }

    @GetMapping("/types-actes")
    public List<TypeActeReponse> typesActes() {
        return Arrays.stream(TypeActe.values()).map(TypeActeReponse::de).toList();
    }

    @PostMapping("/demandes")
    public ResponseEntity<DemandeReponse> creer(UsagerCourant usager, @Valid @RequestBody CreerDemandeRequete requete) {
        DemandeActe d = service.creer(usager.id(), requete.typeActe(), requete.nombreCopies());
        return ResponseEntity.created(URI.create("/api/demandes/" + d.getId())).body(DemandeReponse.de(d));
    }

    @GetMapping("/demandes")
    public List<DemandeReponse> lister(UsagerCourant usager) {
        return service.lister(usager.id()).stream().map(DemandeReponse::de).toList();
    }

    @GetMapping("/demandes/{id}")
    public DemandeReponse consulter(UsagerCourant usager, @PathVariable UUID id) {
        return DemandeReponse.de(service.consulter(usager.id(), id));
    }
}
