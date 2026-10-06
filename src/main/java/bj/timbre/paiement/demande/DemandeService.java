package bj.timbre.paiement.demande;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bj.timbre.paiement.commun.ErreurMetier;
import bj.timbre.paiement.paiement.PaiementRepository;
import bj.timbre.paiement.paiement.StatutPaiement;

@Service
public class DemandeService {

    private final DemandeActeRepository demandes;
    private final PaiementRepository paiements;
    private final Clock clock;

    public DemandeService(DemandeActeRepository demandes, PaiementRepository paiements, Clock clock) {
        this.demandes = demandes;
        this.paiements = paiements;
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

    /** Déduit du seul paiement actif (EN_COURS ou REUSSI) de la demande, s'il existe. */
    @Transactional(readOnly = true)
    public StatutDemande statut(UUID demandeId) {
        return paiements.findByDemandeVerrou(demandeId)
                .map(p -> p.getStatut() == StatutPaiement.REUSSI ? StatutDemande.PAYEE : StatutDemande.PAIEMENT_EN_COURS)
                .orElse(StatutDemande.A_PAYER);
    }
}
