package bj.taxstamp.payment.simulator;

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

import bj.taxstamp.payment.operator.Acknowledgement;
import bj.taxstamp.payment.operator.OperatorStatusQuery;
import bj.taxstamp.payment.operator.DebitRequest;
import bj.taxstamp.payment.operator.OperatorResultMessage;
import bj.taxstamp.payment.operator.OperatorUnavailableException;
import bj.taxstamp.payment.operator.HmacSignature;
import bj.taxstamp.payment.payment.PaymentProperties;
import jakarta.annotation.PreDestroy;

/**
 * Opérateur de mobile money simulé, le plus simplement possible : un registre en
 * mémoire des débits reçus, et un rappel HTTP signé vers le service.
 *
 * Issue automatique (mode AUTO) selon la fin du numéro :
 * <ul>
 *   <li>...00 → FAILURE (solde insuffisant)</li>
 *   <li>...99 → aucune réponse (pour éprouver la réconciliation)</li>
 *   <li>sinon → SUCCESS</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "simulator.enabled", havingValue = "true")
public class OperatorSimulator {

    private static final Logger log = LoggerFactory.getLogger(OperatorSimulator.class);

    private final SimulatorProperties properties;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> callsByReference = new ConcurrentHashMap<>();
    /** Pour éprouver le service : l'opérateur enregistre le débit mais l'accusé se perd en route. */
    private final AtomicBoolean loseAcknowledgements = new AtomicBoolean(false);
    private volatile int servicePort = 8080;

    public OperatorSimulator(SimulatorProperties properties, PaymentProperties paymentProperties,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.paymentProperties = paymentProperties;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onStartup(WebServerInitializedEvent event) {
        this.servicePort = event.getWebServer().getPort();
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ API "opérateur"

    public Acknowledgement receiveDebitRequest(DebitRequest request) {
        callsByReference.computeIfAbsent(request.reference(), r -> new AtomicInteger()).incrementAndGet();

        AtomicBoolean isNew = new AtomicBoolean(false);
        // Idempotence côté opérateur : une même référence n'est enregistrée qu'une fois.
        Transaction t = transactions.computeIfAbsent(request.reference(), r -> {
            isNew.set(true);
            return new Transaction(request, "OP-" + request.operator() + "-" + UUID.randomUUID().toString().substring(0, 8));
        });
        log.info("[SIMULATEUR {}] débit {} FCFA reçu sur {} (réf. {}){}", request.operator(), request.amount(),
                request.phoneNumber(), request.reference(), isNew.get() ? "" : " — doublon ignoré");

        if (isNew.get() && properties.mode() == SimulatorProperties.Mode.AUTO) {
            scheduleAutomaticOutcome(t);
        }
        if (loseAcknowledgements.get()) {
            throw new OperatorUnavailableException("Accusé de réception perdu (simulé)");
        }
        return new Acknowledgement(t.operatorReference);
    }

    public OperatorStatusQuery queryStatus(String reference) {
        Transaction t = transactions.get(reference);
        if (t == null) {
            return new OperatorStatusQuery(OperatorStatusQuery.State.UNKNOWN, null, null);
        }
        synchronized (t) {
            return t.snapshot();
        }
    }

    public OperatorStatusQuery cancel(String reference) {
        Transaction t = transactions.get(reference);
        if (t == null) {
            return new OperatorStatusQuery(OperatorStatusQuery.State.UNKNOWN, null, null);
        }
        synchronized (t) {
            if (t.state == OperatorStatusQuery.State.PENDING) {
                t.state = OperatorStatusQuery.State.CANCELLED;
                t.reason = "Annulé à la demande du service";
            }
            return t.snapshot();
        }
    }

    // ------------------------------------------------------------------ pilotage (démo / tests)

    /**
     * Fixe l'issue d'un débit encore en attente puis envoie le rappel signé.
     *
     * @return false si la transaction est inconnue ou n'est plus en attente.
     */
    public boolean sendResult(String reference, boolean success, String reason) {
        Transaction t = transactions.get(reference);
        if (t == null) {
            return false;
        }
        synchronized (t) {
            if (t.state != OperatorStatusQuery.State.PENDING) {
                return false;
            }
            t.state = success ? OperatorStatusQuery.State.SUCCESS : OperatorStatusQuery.State.FAILURE;
            t.reason = success ? null : (reason == null ? "Refusé par l'abonné" : reason);
        }
        sendCallback(t);
        return true;
    }

    /** Fixe l'issue sans envoyer de rappel : simule un rappel perdu en route. */
    public boolean setOutcomeWithoutCallback(String reference, boolean success, String reason) {
        Transaction t = transactions.get(reference);
        if (t == null) {
            return false;
        }
        synchronized (t) {
            if (t.state != OperatorStatusQuery.State.PENDING) {
                return false;
            }
            t.state = success ? OperatorStatusQuery.State.SUCCESS : OperatorStatusQuery.State.FAILURE;
            t.reason = success ? null : reason;
            return true;
        }
    }

    /** Renvoie le dernier résultat (simule une relance de l'opérateur, donc un doublon). */
    public boolean resendResult(String reference) {
        Transaction t = transactions.get(reference);
        if (t == null || t.state == OperatorStatusQuery.State.PENDING || t.state == OperatorStatusQuery.State.CANCELLED) {
            return false;
        }
        sendCallback(t);
        return true;
    }

    public void simulateLostAcknowledgements(boolean active) {
        loseAcknowledgements.set(active);
    }

    public int debitRequestCount(String reference) {
        AtomicInteger n = callsByReference.get(reference);
        return n == null ? 0 : n.get();
    }

    public Collection<Map<String, Object>> list() {
        List<Map<String, Object>> view = new ArrayList<>();
        transactions.values().forEach(t -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reference", t.request.reference());
            row.put("operatorReference", t.operatorReference);
            row.put("operator", t.request.operator());
            row.put("phoneNumber", t.request.phoneNumber());
            row.put("amount", t.request.amount());
            row.put("state", t.state);
            row.put("reason", t.reason);
            row.put("requestsReceived", debitRequestCount(t.request.reference()));
            row.put("receivedAt", t.receivedAt);
            view.add(row);
        });
        return view;
    }

    // ------------------------------------------------------------------ interne

    private void scheduleAutomaticOutcome(Transaction t) {
        String phone = t.request.phoneNumber();
        if (phone.endsWith("99")) {
            log.info("[SIMULATEUR] réf. {} : aucune réponse ne sera envoyée", t.request.reference());
            return;
        }
        boolean success = !phone.endsWith("00");
        scheduler.schedule(
                () -> sendResult(t.request.reference(), success, success ? null : "Solde insuffisant"),
                properties.resultDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void sendCallback(Transaction t) {
        OperatorResultMessage message;
        synchronized (t) {
            message = new OperatorResultMessage(
                    t.request.reference(),
                    t.operatorReference,
                    t.request.operator(),
                    t.state == OperatorStatusQuery.State.SUCCESS ? OperatorResultMessage.SUCCESS : OperatorResultMessage.FAILURE,
                    t.request.amount(),
                    t.reason);
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(message);
            String signature = HmacSignature.sign(paymentProperties.secretFor(t.request.operator()), body);
            restClient.post()
                    .uri(callbackUrlFor(t))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", signature)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[SIMULATEUR] résultat {} envoyé pour la réf. {}", message.status(), message.reference());
        } catch (JsonProcessingException | RestClientException e) {
            // Rappel perdu : c'est précisément le cas que la réconciliation du service rattrape.
            log.warn("[SIMULATEUR] échec d'envoi du rappel pour {} : {}", t.request.reference(), e.getMessage());
        }
    }

    private String callbackUrlFor(Transaction t) {
        String base = properties.callbackUrl();
        if (base == null || base.isBlank()) {
            base = "http://localhost:" + servicePort;
        }
        return base + "/api/operators/" + t.request.operator() + "/results";
    }

    private static final class Transaction {
        private final DebitRequest request;
        private final String operatorReference;
        private final Instant receivedAt = Instant.now();
        private volatile OperatorStatusQuery.State state = OperatorStatusQuery.State.PENDING;
        private volatile String reason;

        private Transaction(DebitRequest request, String operatorReference) {
            this.request = request;
            this.operatorReference = operatorReference;
        }

        private OperatorStatusQuery snapshot() {
            return new OperatorStatusQuery(state, operatorReference, reason);
        }
    }
}
