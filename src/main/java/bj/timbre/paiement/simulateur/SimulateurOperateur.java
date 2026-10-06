package bj.timbre.paiement.simulateur;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import bj.timbre.paiement.operateur.AccuseReception;
import bj.timbre.paiement.operateur.ConsultationOperateur;
import bj.timbre.paiement.operateur.DemandeDebit;
import bj.timbre.paiement.operateur.MessageResultatOperateur;
import bj.timbre.paiement.operateur.OperateurIndisponibleException;
import bj.timbre.paiement.operateur.SignatureHmac;
import bj.timbre.paiement.paiement.PaiementProperties;
import jakarta.annotation.PreDestroy;

/**
 * Opérateur de mobile money simulé, le plus simplement possible : un registre en
 * mémoire des débits reçus, et un rappel HTTP signé vers le service.
 *
 * Issue automatique (mode AUTO) selon la fin du numéro :
 * <ul>
 *   <li>...00 → ECHEC (solde insuffisant)</li>
 *   <li>...99 → aucune réponse (pour éprouver la réconciliation)</li>
 *   <li>sinon → SUCCES</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "simulateur.actif", havingValue = "true")
public class SimulateurOperateur {

    private static final Logger log = LoggerFactory.getLogger(SimulateurOperateur.class);

    private final SimulateurProperties proprietes;
    private final PaiementProperties paiementProprietes;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();
    private final ScheduledExecutorService planificateur = Executors.newSingleThreadScheduledExecutor();

    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> appelsParReference = new ConcurrentHashMap<>();
    /** Pour éprouver le service : l'opérateur enregistre le débit mais l'accusé se perd en route. */
    private final AtomicBoolean perteDesAccuses = new AtomicBoolean(false);
    private volatile int portService = 8080;

    public SimulateurOperateur(SimulateurProperties proprietes, PaiementProperties paiementProprietes,
                               ObjectMapper objectMapper) {
        this.proprietes = proprietes;
        this.paiementProprietes = paiementProprietes;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void surDemarrage(WebServerInitializedEvent evenement) {
        this.portService = evenement.getWebServer().getPort();
    }

    @PreDestroy
    public void arreter() {
        planificateur.shutdownNow();
    }

    // ------------------------------------------------------------------ API "opérateur"

    public AccuseReception recevoirDemandeDebit(DemandeDebit demande) {
        appelsParReference.computeIfAbsent(demande.reference(), r -> new AtomicInteger()).incrementAndGet();

        AtomicBoolean nouvelle = new AtomicBoolean(false);
        // Idempotence côté opérateur : une même référence n'est enregistrée qu'une fois.
        Transaction t = transactions.computeIfAbsent(demande.reference(), r -> {
            nouvelle.set(true);
            return new Transaction(demande, "OP-" + demande.operateur() + "-" + UUID.randomUUID().toString().substring(0, 8));
        });
        log.info("[SIMULATEUR {}] débit {} FCFA reçu sur {} (réf. {}){}", demande.operateur(), demande.montant(),
                demande.telephone(), demande.reference(), nouvelle.get() ? "" : " — doublon ignoré");

        if (nouvelle.get() && proprietes.mode() == SimulateurProperties.Mode.AUTO) {
            planifierIssueAutomatique(t);
        }
        if (perteDesAccuses.get()) {
            throw new OperateurIndisponibleException("Accusé de réception perdu (simulé)");
        }
        return new AccuseReception(t.referenceOperateur);
    }

    public ConsultationOperateur consulter(String reference) {
        Transaction t = transactions.get(reference);
        if (t == null) {
            return new ConsultationOperateur(ConsultationOperateur.Etat.INCONNU, null, null);
        }
        synchronized (t) {
            return t.consultation();
        }
    }

    public ConsultationOperateur annuler(String reference) {
        Transaction t = transactions.get(reference);
        if (t == null) {
            return new ConsultationOperateur(ConsultationOperateur.Etat.INCONNU, null, null);
        }
        synchronized (t) {
            if (t.etat == ConsultationOperateur.Etat.EN_ATTENTE) {
                t.etat = ConsultationOperateur.Etat.ANNULE;
                t.motif = "Annulé à la demande du service";
            }
            return t.consultation();
        }
    }

    // ------------------------------------------------------------------ pilotage (démo / tests)

    /**
     * Fixe l'issue d'un débit encore en attente puis envoie le rappel signé.
     *
     * @return false si la transaction est inconnue ou n'est plus en attente.
     */
    public boolean transmettreResultat(String reference, boolean succes, String motif) {
        Transaction t = transactions.get(reference);
        if (t == null) {
            return false;
        }
        synchronized (t) {
            if (t.etat != ConsultationOperateur.Etat.EN_ATTENTE) {
                return false;
            }
            t.etat = succes ? ConsultationOperateur.Etat.SUCCES : ConsultationOperateur.Etat.ECHEC;
            t.motif = succes ? null : (motif == null ? "Refusé par l'abonné" : motif);
        }
        envoyerRappel(t);
        return true;
    }

    /** Fixe l'issue sans envoyer de rappel : simule un rappel perdu en route. */
    public boolean fixerIssueSansRappel(String reference, boolean succes, String motif) {
        Transaction t = transactions.get(reference);
        if (t == null) {
            return false;
        }
        synchronized (t) {
            if (t.etat != ConsultationOperateur.Etat.EN_ATTENTE) {
                return false;
            }
            t.etat = succes ? ConsultationOperateur.Etat.SUCCES : ConsultationOperateur.Etat.ECHEC;
            t.motif = succes ? null : motif;
            return true;
        }
    }

    /** Renvoie le dernier résultat (simule une relance de l'opérateur, donc un doublon). */
    public boolean renvoyerResultat(String reference) {
        Transaction t = transactions.get(reference);
        if (t == null || t.etat == ConsultationOperateur.Etat.EN_ATTENTE || t.etat == ConsultationOperateur.Etat.ANNULE) {
            return false;
        }
        envoyerRappel(t);
        return true;
    }

    public void simulerPerteDesAccuses(boolean active) {
        perteDesAccuses.set(active);
    }

    public int nombreDemandesDebitRecues(String reference) {
        AtomicInteger n = appelsParReference.get(reference);
        return n == null ? 0 : n.get();
    }

    public Collection<Map<String, Object>> lister() {
        List<Map<String, Object>> vue = new ArrayList<>();
        transactions.values().forEach(t -> {
            Map<String, Object> ligne = new LinkedHashMap<>();
            ligne.put("reference", t.demande.reference());
            ligne.put("referenceOperateur", t.referenceOperateur);
            ligne.put("operateur", t.demande.operateur());
            ligne.put("telephone", t.demande.telephone());
            ligne.put("montant", t.demande.montant());
            ligne.put("etat", t.etat);
            ligne.put("motif", t.motif);
            ligne.put("demandesRecues", nombreDemandesDebitRecues(t.demande.reference()));
            ligne.put("recuLe", t.recuLe);
            vue.add(ligne);
        });
        return vue;
    }

    // ------------------------------------------------------------------ interne

    private void planifierIssueAutomatique(Transaction t) {
        String tel = t.demande.telephone();
        if (tel.endsWith("99")) {
            log.info("[SIMULATEUR] réf. {} : aucune réponse ne sera envoyée", t.demande.reference());
            return;
        }
        boolean succes = !tel.endsWith("00");
        planificateur.schedule(
                () -> transmettreResultat(t.demande.reference(), succes, succes ? null : "Solde insuffisant"),
                proprietes.delaiResultat().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void envoyerRappel(Transaction t) {
        MessageResultatOperateur message;
        synchronized (t) {
            message = new MessageResultatOperateur(
                    t.demande.reference(),
                    t.referenceOperateur,
                    t.demande.operateur(),
                    t.etat == ConsultationOperateur.Etat.SUCCES ? MessageResultatOperateur.SUCCES : MessageResultatOperateur.ECHEC,
                    t.demande.montant(),
                    t.motif);
        }
        try {
            byte[] corps = objectMapper.writeValueAsBytes(message);
            String signature = SignatureHmac.signer(paiementProprietes.secretDe(t.demande.operateur()), corps);
            restClient.post()
                    .uri(urlRappel(t))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", signature)
                    .body(corps)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[SIMULATEUR] résultat {} envoyé pour la réf. {}", message.statut(), message.reference());
        } catch (JsonProcessingException | RestClientException e) {
            // Rappel perdu : c'est précisément le cas que la réconciliation du service rattrape.
            log.warn("[SIMULATEUR] échec d'envoi du rappel pour {} : {}", t.demande.reference(), e.getMessage());
        }
    }

    private String urlRappel(Transaction t) {
        String base = proprietes.urlCallback();
        if (base == null || base.isBlank()) {
            base = "http://localhost:" + portService;
        }
        return base + "/api/operateurs/" + t.demande.operateur() + "/resultats";
    }

    private static final class Transaction {
        private final DemandeDebit demande;
        private final String referenceOperateur;
        private final Instant recuLe = Instant.now();
        private volatile ConsultationOperateur.Etat etat = ConsultationOperateur.Etat.EN_ATTENTE;
        private volatile String motif;

        private Transaction(DemandeDebit demande, String referenceOperateur) {
            this.demande = demande;
            this.referenceOperateur = referenceOperateur;
        }

        private ConsultationOperateur consultation() {
            return new ConsultationOperateur(etat, referenceOperateur, motif);
        }
    }
}
