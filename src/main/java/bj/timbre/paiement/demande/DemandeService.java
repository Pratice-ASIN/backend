package bj.timbre.paiement.demande;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bj.timbre.paiement.commun.ErreurMetier;

@Service
public class DemandeService {

    private final DemandeActeRepository demandes;
    private final Clock clock;

    public DemandeService(DemandeActeRepository demandes, Clock clock) {
        this.demandes = demandes;
        this.clock = clock;
    }

    @Transactional
    public DemandeActe creer(String usagerId, TypeActe typeActe, int nombreCopies) {
        return demandes.save(DemandeActe.creer(usagerId, typeActe, nombreCopies, clock.instant()));
    }

    @Transactional(readOnly = true)
    public DemandeActe consulter(String usagerId, UUID demandeId) {
        return demandes.findByIdAndUsagerId(demandeId, usagerId)
                .orElseThrow(() -> ErreurMetier.introuvable("Demande"));
    }

    @Transactional(readOnly = true)
    public List<DemandeActe> lister(String usagerId) {
        return demandes.findByUsagerIdOrderByCreeLeDesc(usagerId);
    }
}
