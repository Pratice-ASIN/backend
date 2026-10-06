package bj.timbre.paiement.paiement;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import bj.timbre.paiement.commun.ErreurMetier;
import bj.timbre.paiement.operateur.MessageResultatOperateur;
import bj.timbre.paiement.operateur.SignatureHmac;

/**
 * Prise en compte des résultats transmis par les opérateurs.
 *
 * Ordre des contrôles :
 * <ol>
 *   <li>signature HMAC vérifiée AVANT toute lecture du contenu ;</li>
 *   <li>le paiement existe et appartient bien à cet opérateur ;</li>
 *   <li>le montant annoncé est celui du paiement (sinon rejet, rien n'est modifié) ;</li>
 *   <li>transition EN_COURS → état final sous verrou ligne ; un paiement déjà finalisé
 *       n'est jamais modifié (doublons et rejeux sans effet).</li>
 * </ol>
 */
@Service
public class ResultatOperateurService {

    private static final Logger log = LoggerFactory.getLogger(ResultatOperateurService.class);

    private final PaiementRepository paiements;
    private final PaiementTransactions transactions;
    private final PaiementProperties proprietes;
    private final ObjectReader lecteur;

    public ResultatOperateurService(PaiementRepository paiements, PaiementTransactions transactions,
                                    PaiementProperties proprietes, ObjectMapper objectMapper) {
        this.paiements = paiements;
        this.transactions = transactions;
        this.proprietes = proprietes;
        // Tolérant aux champs ajoutés par l'opérateur (le message est déjà authentifié à ce stade).
        this.lecteur = objectMapper.readerFor(MessageResultatOperateur.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public record Traitement(UUID paiementId, StatutPaiement statut, boolean prisEnCompte) {
    }

    public Traitement traiter(Operateur operateur, byte[] corps, String signature) {
        if (!SignatureHmac.verifier(proprietes.secretDe(operateur), corps, signature)) {
            log.warn("Résultat {} rejeté : signature absente ou invalide", operateur);
            throw new ErreurMetier(HttpStatus.UNAUTHORIZED, "SIGNATURE_INVALIDE", "Signature invalide");
        }

        MessageResultatOperateur message = lire(corps);
        Paiement paiement = trouver(message.reference())
                .filter(p -> p.getOperateur() == operateur)
                .orElseThrow(() -> ErreurMetier.introuvable("Paiement"));

        if (message.operateur() != null && message.operateur() != operateur) {
            throw new ErreurMetier(HttpStatus.UNPROCESSABLE_ENTITY, "OPERATEUR_INCOHERENT",
                    "L'opérateur du message ne correspond pas à l'émetteur");
        }
        if (message.montant() == null || message.montant() != paiement.getMontant()) {
            log.error("ANOMALIE paiement {} : montant annoncé {} ≠ montant dû {}. Résultat non appliqué.",
                    paiement.getId(), message.montant(), paiement.getMontant());
            throw new ErreurMetier(HttpStatus.UNPROCESSABLE_ENTITY, "MONTANT_INCOHERENT",
                    "Le montant annoncé ne correspond pas au montant du paiement");
        }

        StatutPaiement statut = switch (String.valueOf(message.statut())) {
            case MessageResultatOperateur.SUCCES -> StatutPaiement.REUSSI;
            case MessageResultatOperateur.ECHEC -> StatutPaiement.ECHOUE;
            default -> throw new ErreurMetier(HttpStatus.BAD_REQUEST, "STATUT_INCONNU",
                    "Statut attendu : SUCCES ou ECHEC");
        };

        Optional<Paiement> maj = transactions.appliquerResultat(paiement.getId(), statut,
                message.referenceOperateur(), statut == StatutPaiement.ECHOUE ? message.motif() : null);
        if (maj.isPresent()) {
            return new Traitement(paiement.getId(), maj.get().getStatut(), true);
        }
        // Déjà finalisé : on acquitte quand même (200) pour que l'opérateur cesse ses relances.
        return new Traitement(paiement.getId(), transactions.recharger(paiement.getId()).getStatut(), false);
    }

    private MessageResultatOperateur lire(byte[] corps) {
        try {
            return lecteur.readValue(corps);
        } catch (IOException e) {
            throw new ErreurMetier(HttpStatus.BAD_REQUEST, "MESSAGE_ILLISIBLE", "Message de l'opérateur illisible");
        }
    }

    private Optional<Paiement> trouver(String reference) {
        try {
            return reference == null ? Optional.empty() : paiements.findById(UUID.fromString(reference));
        } catch (IllegalArgumentException referenceMalFormee) {
            return Optional.empty();
        }
    }
}
