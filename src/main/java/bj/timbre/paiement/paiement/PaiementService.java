package bj.timbre.paiement.paiement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bj.timbre.paiement.commun.ErreurMetier;
import bj.timbre.paiement.demande.DemandeActeRepository;
import bj.timbre.paiement.operateur.AccuseReception;
import bj.timbre.paiement.operateur.DebitRefuseException;
import bj.timbre.paiement.operateur.DemandeDebit;
import bj.timbre.paiement.operateur.OperateurClient;
import bj.timbre.paiement.operateur.OperateurIndisponibleException;

@Service
public class PaiementService {

    private static final Logger log = LoggerFactory.getLogger(PaiementService.class);
    private static final int TAILLE_MAX_CLE = 100;
    private static final int ESSAIS_RESOLUTION = 20;
    private static final long PAUSE_RESOLUTION_MS = 50;

    private final PaiementTransactions transactions;
    private final PaiementRepository paiements;
    private final DemandeActeRepository demandes;
    private final OperateurClient operateurClient;

    public PaiementService(PaiementTransactions transactions, PaiementRepository paiements,
                           DemandeActeRepository demandes, OperateurClient operateurClient) {
        this.transactions = transactions;
        this.paiements = paiements;
        this.demandes = demandes;
        this.operateurClient = operateurClient;
    }

    public record Lancement(Paiement paiement, boolean nouveau) {
    }

    /**
     * Lance le paiement d'une demande.
     *
     * <ol>
     *   <li>Validations (aucun débit pour un paiement invalide).</li>
     *   <li>Enregistrement EN_COURS en transaction ; la base garantit un seul paiement
     *       actif par demande, même pour deux requêtes simultanées.</li>
     *   <li>Seul le gagnant, après commit, demande le débit à l'opérateur.</li>
     * </ol>
     */
    public Lancement lancer(String usagerId, UUID demandeId, String telephoneBrut, Operateur operateur,
                            String cleIdempotence) {
        String telephone = telephoneBrut == null ? null : telephoneBrut.trim();
        if (!Telephone.estValide(telephone)) {
            throw new ErreurMetier(HttpStatus.BAD_REQUEST, "TELEPHONE_INVALIDE",
                    "Le numéro doit comporter 10 chiffres et commencer par 01");
        }
        if (operateur == null) {
            throw new ErreurMetier(HttpStatus.BAD_REQUEST, "OPERATEUR_INVALIDE", "Opérateur obligatoire");
        }
        String cle = normaliserCle(cleIdempotence);

        PaiementTransactions.CreationPaiement creation;
        try {
            creation = transactions.creer(usagerId, demandeId, telephone, operateur, cle);
        } catch (DataAccessException courseConcurrente) {
            // Contrainte unique violée (ou verrou) : une requête concurrente a gagné.
            // Aucun débit n'a été demandé par ce fil d'exécution.
            log.info("Requête de paiement concurrente détectée pour la demande {}", demandeId);
            return new Lancement(attendreGagnant(usagerId, demandeId, telephone, operateur, cle), false);
        }

        if (!creation.nouveau()) {
            return new Lancement(creation.paiement(), false);
        }
        demanderDebit(creation.paiement());
        return new Lancement(transactions.recharger(creation.paiement().getId()), true);
    }

    /**
     * Selon la base, la violation peut être signalée avant que la transaction gagnante
     * soit validée : on lui laisse quelques instants pour devenir visible.
     */
    private Paiement attendreGagnant(String usagerId, UUID demandeId, String telephone, Operateur operateur,
                                     String cle) {
        for (int essai = 0; essai < ESSAIS_RESOLUTION; essai++) {
            Optional<Paiement> gagnant = transactions.resoudreApresConflit(usagerId, demandeId, telephone, operateur, cle);
            if (gagnant.isPresent()) {
                return gagnant.get();
            }
            try {
                Thread.sleep(PAUSE_RESOLUTION_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ErreurMetier(HttpStatus.CONFLICT, "CONFLIT_CONCURRENT",
                "Une autre demande de paiement a été traitée au même moment, veuillez réessayer");
    }

    private void demanderDebit(Paiement p) {
        DemandeDebit demande = new DemandeDebit(p.getOperateur(), p.getId().toString(), p.getTelephone(), p.getMontant());
        try {
            AccuseReception accuse = operateurClient.demanderDebit(demande);
            transactions.enregistrerAccuse(p.getId(), accuse.referenceOperateur());
        } catch (DebitRefuseException refus) {
            transactions.appliquerResultat(p.getId(), StatutPaiement.ECHOUE, null,
                    "Refusé par l'opérateur : " + refus.getMessage());
        } catch (OperateurIndisponibleException incertain) {
            // Issue inconnue : surtout NE PAS marquer en échec (le débit a pu être pris en compte).
            // Le paiement reste EN_COURS ; le résultat signé ou la réconciliation trancheront.
            log.warn("Débit {} sans accusé de réception ({}), réconciliation à venir",
                    p.getId(), incertain.getMessage());
        } catch (RuntimeException incertain) {
            // Même règle pour une erreur inattendue : issue inconnue, on ne conclut pas.
            log.error("Erreur inattendue lors de la demande de débit {}, réconciliation à venir", p.getId(), incertain);
        }
    }

    @Transactional(readOnly = true)
    public Paiement consulter(String usagerId, UUID paiementId) {
        return paiements.findByIdAndUsagerId(paiementId, usagerId)
                .orElseThrow(() -> ErreurMetier.introuvable("Paiement"));
    }

    @Transactional(readOnly = true)
    public List<Paiement> historique(String usagerId, UUID demandeId) {
        demandes.findByIdAndUsagerId(demandeId, usagerId).orElseThrow(() -> ErreurMetier.introuvable("Demande"));
        return paiements.findByDemandeIdOrderByCreeLeDesc(demandeId);
    }

    private static String normaliserCle(String cle) {
        if (cle == null) {
            return null;
        }
        String c = cle.trim();
        if (c.isEmpty() || c.length() > TAILLE_MAX_CLE) {
            throw new ErreurMetier(HttpStatus.BAD_REQUEST, "CLE_IDEMPOTENCE_INVALIDE",
                    "En-tête Idempotency-Key vide ou trop long (100 caractères max)");
        }
        return c;
    }
}
