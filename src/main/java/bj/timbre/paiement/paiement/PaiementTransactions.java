package bj.timbre.paiement.paiement;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bj.timbre.paiement.commun.ErreurMetier;
import bj.timbre.paiement.demande.DemandeActe;
import bj.timbre.paiement.demande.DemandeActeRepository;

/**
 * Unités transactionnelles courtes du paiement.
 *
 * Volontairement séparées de {@link PaiementService} : l'appel à l'opérateur ne doit
 * JAMAIS se faire à l'intérieur d'une transaction. Le paiement est d'abord enregistré
 * et validé (commit), puis seul le fil d'exécution qui l'a créé demande le débit.
 */
@Service
public class PaiementTransactions {

    private static final Logger log = LoggerFactory.getLogger(PaiementTransactions.class);

    private final PaiementRepository paiements;
    private final DemandeActeRepository demandes;
    private final Clock clock;

    public PaiementTransactions(PaiementRepository paiements, DemandeActeRepository demandes, Clock clock) {
        this.paiements = paiements;
        this.demandes = demandes;
        this.clock = clock;
    }

    public record CreationPaiement(Paiement paiement, boolean nouveau) {
    }

    /**
     * Crée le paiement EN_COURS si la demande est payable.
     * En cas de course avec une requête identique, la contrainte unique fait échouer
     * l'insertion perdante ({@code DataIntegrityViolationException}) : voir
     * {@link #resoudreApresConflit}.
     */
    @Transactional
    public CreationPaiement creer(String usagerId, UUID demandeId, String telephone, Operateur operateur,
                                  String cleIdempotence) {
        DemandeActe demande = demandes.findByIdAndUsagerId(demandeId, usagerId)
                .orElseThrow(() -> ErreurMetier.introuvable("Demande"));

        if (cleIdempotence != null) {
            Optional<Paiement> rejeu = paiements.findByUsagerIdAndCleIdempotence(usagerId, cleIdempotence);
            if (rejeu.isPresent()) {
                return new CreationPaiement(verifierRejeu(rejeu.get(), demandeId, telephone, operateur), false);
            }
        }

        paiements.findByDemandeVerrou(demandeId).ifPresent(actif -> {
            throw conflit(actif);
        });

        Paiement paiement = Paiement.nouveau(demande, usagerId, telephone, operateur, cleIdempotence, clock.instant());
        // flush immédiat : la violation de contrainte éventuelle survient ici, pas au commit.
        return new CreationPaiement(paiements.saveAndFlush(paiement), true);
    }

    /**
     * Appelé après une insertion perdue face à une requête concurrente (nouvelle transaction).
     *
     * @return le paiement gagnant si la requête est un rejeu (même clé) ; vide si le
     *         gagnant n'est pas encore visible (transaction concurrente pas encore validée).
     * @throws ErreurMetier 409 si un autre paiement actif occupe la demande.
     */
    @Transactional(readOnly = true)
    public Optional<Paiement> resoudreApresConflit(String usagerId, UUID demandeId, String telephone,
                                                   Operateur operateur, String cleIdempotence) {
        if (cleIdempotence != null) {
            Optional<Paiement> rejeu = paiements.findByUsagerIdAndCleIdempotence(usagerId, cleIdempotence);
            if (rejeu.isPresent()) {
                return Optional.of(verifierRejeu(rejeu.get(), demandeId, telephone, operateur));
            }
        }
        paiements.findByDemandeVerrou(demandeId).ifPresent(actif -> {
            throw conflit(actif);
        });
        return Optional.empty();
    }

    @Transactional
    public void enregistrerAccuse(UUID paiementId, String referenceOperateur) {
        paiements.findPourMiseAJour(paiementId)
                .ifPresent(p -> p.enregistrerReferenceOperateur(referenceOperateur, clock.instant()));
    }

    /**
     * Applique un résultat final sous verrou ligne.
     *
     * @return le paiement mis à jour, ou vide s'il était déjà finalisé (résultat ignoré).
     */
    @Transactional
    public Optional<Paiement> appliquerResultat(UUID paiementId, StatutPaiement statut, String referenceOperateur,
                                                String motif) {
        Paiement p = paiements.findPourMiseAJour(paiementId)
                .orElseThrow(() -> ErreurMetier.introuvable("Paiement"));
        if (p.getStatut().estFinal()) {
            if (p.getStatut() != statut) {
                log.warn("Résultat contradictoire ignoré pour le paiement {} : déjà {}, reçu {}",
                        paiementId, p.getStatut(), statut);
            } else {
                log.info("Résultat en double ignoré pour le paiement {} ({})", paiementId, statut);
            }
            return Optional.empty();
        }
        p.finaliser(statut, referenceOperateur, motif, clock.instant());
        log.info("Paiement {} finalisé : {}", paiementId, statut);
        return Optional.of(p);
    }

    @Transactional(readOnly = true)
    public Paiement recharger(UUID paiementId) {
        return paiements.findById(paiementId).orElseThrow(() -> ErreurMetier.introuvable("Paiement"));
    }

    private static Paiement verifierRejeu(Paiement existant, UUID demandeId, String telephone, Operateur operateur) {
        boolean memeRequete = existant.getDemandeId().equals(demandeId)
                && existant.getTelephone().equals(telephone)
                && existant.getOperateur() == operateur;
        if (!memeRequete) {
            throw new ErreurMetier(HttpStatus.UNPROCESSABLE_ENTITY, "CLE_IDEMPOTENCE_REUTILISEE",
                    "Cette clé d'idempotence a déjà servi pour une autre requête de paiement");
        }
        return existant;
    }

    private static ErreurMetier conflit(Paiement actif) {
        Map<String, Object> details = Map.of("paiementId", actif.getId(), "statut", actif.getStatut());
        if (actif.getStatut() == StatutPaiement.REUSSI) {
            return new ErreurMetier(HttpStatus.CONFLICT, "DEMANDE_DEJA_PAYEE",
                    "Cette demande est déjà payée", details);
        }
        return new ErreurMetier(HttpStatus.CONFLICT, "PAIEMENT_EN_COURS",
                "Un paiement est déjà en cours pour cette demande", details);
    }
}
