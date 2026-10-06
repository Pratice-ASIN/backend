package bj.timbre.paiement.paiement;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import bj.timbre.paiement.operateur.ConsultationOperateur;
import bj.timbre.paiement.operateur.OperateurClient;

/**
 * Traite les paiements dont le résultat n'arrive pas (rappel perdu, accusé perdu,
 * opérateur muet).
 *
 * <ul>
 *   <li>Après {@code delai-consultation} : on interroge l'opérateur et on applique
 *       l'état qu'il connaît (succès, échec, ou débit jamais reçu).</li>
 *   <li>Après {@code delai-expiration}, si le débit est toujours en attente : on en
 *       demande l'annulation. Le paiement ne passe EXPIRE que si l'opérateur confirme
 *       l'annulation : on ne libère jamais la demande tant qu'un débit peut encore
 *       aboutir, sinon un nouvel essai pourrait provoquer un double débit.</li>
 * </ul>
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaiementRepository paiements;
    private final PaiementTransactions transactions;
    private final OperateurClient operateurClient;
    private final PaiementProperties proprietes;

    public ReconciliationService(PaiementRepository paiements, PaiementTransactions transactions,
                                 OperateurClient operateurClient, PaiementProperties proprietes) {
        this.paiements = paiements;
        this.transactions = transactions;
        this.operateurClient = operateurClient;
        this.proprietes = proprietes;
    }

    /** @return le nombre de paiements finalisés lors de ce passage. */
    public int reconcilier(Instant maintenant) {
        List<Paiement> enAttente = paiements.findTop100ByStatutAndCreeLeBeforeOrderByCreeLeAsc(
                StatutPaiement.EN_COURS, maintenant.minus(proprietes.delaiConsultation()));
        int finalises = 0;
        for (Paiement p : enAttente) {
            try {
                if (reconcilier(p, maintenant)) {
                    finalises++;
                }
            } catch (RuntimeException e) {
                // Opérateur injoignable : on retentera au prochain passage.
                log.warn("Réconciliation du paiement {} reportée : {}", p.getId(), e.getMessage());
            }
        }
        return finalises;
    }

    private boolean reconcilier(Paiement p, Instant maintenant) {
        String reference = p.getId().toString();
        ConsultationOperateur etat = operateurClient.consulter(p.getOperateur(), reference);

        if (etat.etat() == ConsultationOperateur.Etat.EN_ATTENTE) {
            boolean expire = p.getCreeLe().isBefore(maintenant.minus(proprietes.delaiExpiration()));
            if (!expire) {
                return false;
            }
            etat = operateurClient.annuler(p.getOperateur(), reference);
        }
        return appliquer(p, etat);
    }

    private boolean appliquer(Paiement p, ConsultationOperateur etat) {
        return switch (etat.etat()) {
            case SUCCES -> transactions.appliquerResultat(p.getId(), StatutPaiement.REUSSI,
                    etat.referenceOperateur(), null).isPresent();
            case ECHEC -> transactions.appliquerResultat(p.getId(), StatutPaiement.ECHOUE,
                    etat.referenceOperateur(), etat.motif()).isPresent();
            case INCONNU -> transactions.appliquerResultat(p.getId(), StatutPaiement.ECHOUE,
                    null, "Débit jamais reçu par l'opérateur").isPresent();
            case ANNULE -> transactions.appliquerResultat(p.getId(), StatutPaiement.EXPIRE,
                    etat.referenceOperateur(), "Aucun résultat de l'opérateur : débit annulé").isPresent();
            case EN_ATTENTE -> false; // annulation refusée : on garde la main, prochain passage.
        };
    }
}
