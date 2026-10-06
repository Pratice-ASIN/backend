package bj.taxstamp.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import bj.taxstamp.payment.operator.HmacSignature;
import bj.taxstamp.payment.payment.Payment;
import bj.taxstamp.payment.payment.PaymentRepository;
import bj.taxstamp.payment.payment.ReconciliationService;
import bj.taxstamp.payment.request.DocumentTypeRepository;
import bj.taxstamp.payment.request.DocumentTypeSeeder;
import bj.taxstamp.payment.simulator.OperatorSimulator;

/**
 * Tests de bout en bout des cas critiques : vrai serveur HTTP, vraie base (H2),
 * simulateur en mode manuel pour maîtriser le moment où l'opérateur répond.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "simulator.mode=manual",
        "payment.reconciliation.enabled=false"
})
class PaymentIntegrationTest {

    private static final String SECRET_MTN = "secret-mtn-a-changer";
    private static final String SECRET_MOOV = "secret-moov-a-changer";

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;
    @Autowired
    OperatorSimulator simulator;
    @Autowired
    PaymentRepository payments;
    @Autowired
    ReconciliationService reconciliation;
    @Autowired
    DocumentTypeSeeder seeder;
    @Autowired
    DocumentTypeRepository documentTypes;

    @AfterEach
    void resetSimulator() {
        simulator.simulateLostAcknowledgements(false);
    }

    // =====================================================================================
    @Nested
    @DisplayName("Montant et validations")
    class AmountAndValidation {

        @Test
        void amount_is_computed_by_the_service() {
            assertThat(createRequest("alice", "CRIMINAL_RECORD", 3).get("amountDue").asLong()).isEqualTo(4600);
            assertThat(createRequest("alice", "BIRTH_CERTIFICATE", 1).get("amountDue").asLong()).isEqualTo(1100);
            JsonNode residence = createRequest("alice", "RESIDENCE_CERTIFICATE", 2);
            assertThat(residence.get("amountDue").asLong()).isEqualTo(1100);
            assertThat(residence.get("status").asText()).isEqualTo("UNPAID");
        }

        @Test
        void document_types_are_seeded_with_their_price() {
            Response r = call(HttpMethod.GET, "/api/document-types", "alice", null, null);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body().findValuesAsText("code"))
                    .containsExactlyInAnyOrder("BIRTH_CERTIFICATE", "CRIMINAL_RECORD", "RESIDENCE_CERTIFICATE");
            assertThat(r.body().findValues("unitPrice")).extracting(JsonNode::asLong)
                    .containsExactlyInAnyOrder(1000L, 1500L, 500L);
        }

        @Test
        void seeder_is_idempotent() throws Exception {
            seeder.run(null);
            assertThat(documentTypes.count()).isEqualTo(3);
        }

        @Test
        void unknown_document_type_rejected() {
            Response r = call(HttpMethod.POST, "/api/document-requests", "alice", null,
                    "{\"documentType\":\"PASSPORT\",\"copies\":1}");
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.code()).isEqualTo("UNKNOWN_DOCUMENT_TYPE");
        }

        @Test
        void amount_supplied_by_user_is_rejected() {
            Response request = call(HttpMethod.POST, "/api/document-requests", "alice", null,
                    "{\"documentType\":\"BIRTH_CERTIFICATE\",\"copies\":1,\"amount\":5}");
            assertThat(request.status()).isEqualTo(400);

            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            Response payment = call(HttpMethod.POST, "/api/document-requests/" + requestId + "/payments", "alice", null,
                    "{\"phoneNumber\":\"0197000001\",\"operator\":\"MTN\",\"amount\":5}");
            assertThat(payment.status()).isEqualTo(400);
            assertThat(debitsRequested(requestId)).isZero();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0197", "0297123456", "01971234567", "01ABCDEFGH", "+22901971234", "97123456"})
        void no_debit_for_invalid_phone_number(String phoneNumber) {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));

            Response r = start("alice", requestId, phoneNumber, "MTN", null);

            assertThat(r.status()).isEqualTo(400);
            assertThat(r.code()).isEqualTo("INVALID_PHONE_NUMBER");
            assertThat(payments.findByRequestIdOrderByCreatedAtDesc(requestId)).isEmpty();
            assertThat(simulator.list()).noneMatch(t -> phoneNumber.equals(t.get("phoneNumber")));
        }

        @Test
        void unknown_operator_rejected() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            assertThat(start("alice", requestId, "0197000002", "ORANGE", null).status()).isEqualTo(400);
            assertThat(payments.findByRequestIdOrderByCreatedAtDesc(requestId)).isEmpty();
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Cycle de vie du paiement")
    class Lifecycle {

        @Test
        void happy_path_until_success() {
            UUID requestId = id(createRequest("alice", "CRIMINAL_RECORD", 2));

            Response startResponse = start("alice", requestId, "0197000010", "MTN", null);
            assertThat(startResponse.status()).isEqualTo(202);
            assertThat(startResponse.body().get("status").asText()).isEqualTo("PENDING");
            assertThat(startResponse.body().get("amount").asLong()).isEqualTo(3100);
            assertThat(request("alice", requestId).get("status").asText()).isEqualTo("PAYMENT_PENDING");

            UUID paymentId = id(startResponse.body());
            assertThat(simulator.sendResult(paymentId.toString(), true, null)).isTrue();

            assertThat(paymentStatus("alice", paymentId)).isEqualTo("SUCCEEDED");
            assertThat(request("alice", requestId).get("status").asText()).isEqualTo("PAID");
            assertThat(debitsRequested(requestId)).isEqualTo(1);
        }

        @Test
        void pending_or_paid_request_cannot_be_paid_twice() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            UUID paymentId = id(start("alice", requestId, "0197000011", "MTN", null).body());

            Response during = start("alice", requestId, "0197000011", "MOOV", null);
            assertThat(during.status()).isEqualTo(409);
            assertThat(during.code()).isEqualTo("PAYMENT_PENDING");

            simulator.sendResult(paymentId.toString(), true, null);

            Response after = start("alice", requestId, "0197000011", "MTN", null);
            assertThat(after.status()).isEqualTo(409);
            assertThat(after.code()).isEqualTo("REQUEST_ALREADY_PAID");
            assertThat(debitsRequested(requestId)).isEqualTo(1);
        }

        @Test
        void user_can_retry_after_failure() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            UUID first = id(start("alice", requestId, "0197000012", "MOOV", null).body());
            simulator.sendResult(first.toString(), false, "Solde insuffisant");

            JsonNode failure = payment("alice", first);
            assertThat(failure.get("status").asText()).isEqualTo("FAILED");
            assertThat(failure.get("reason").asText()).isEqualTo("Solde insuffisant");
            assertThat(request("alice", requestId).get("status").asText()).isEqualTo("UNPAID");

            Response second = start("alice", requestId, "0197000012", "MOOV", null);
            assertThat(second.status()).isEqualTo(202);
            simulator.sendResult(id(second.body()).toString(), true, null);

            assertThat(request("alice", requestId).get("status").asText()).isEqualTo("PAID");
            assertThat(paymentStatus("alice", first)).isEqualTo("FAILED");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Un seul débit")
    class SingleDebit {

        @Test
        void request_sent_twice_triggers_single_debit() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            String key = UUID.randomUUID().toString();

            Response firstCall = start("alice", requestId, "0197000020", "MTN", key);
            Response replay = start("alice", requestId, "0197000020", "MTN", key);

            assertThat(firstCall.status()).isEqualTo(202);
            assertThat(replay.status()).isEqualTo(200);
            assertThat(id(replay.body())).isEqualTo(id(firstCall.body()));
            assertThat(debitsRequested(requestId)).isEqualTo(1);
        }

        @Test
        void key_reused_for_another_request_is_rejected() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            String key = UUID.randomUUID().toString();
            start("alice", requestId, "0197000021", "MTN", key);

            Response other = start("alice", requestId, "0197999999", "MTN", key);
            assertThat(other.status()).isEqualTo(422);
            assertThat(debitsRequested(requestId)).isEqualTo(1);
        }

        @Test
        void identical_concurrent_requests_single_debit() throws Exception {
            UUID requestId = id(createRequest("alice", "CRIMINAL_RECORD", 1));
            String key = UUID.randomUUID().toString();

            List<Response> responses = inBurst(20, () -> start("alice", requestId, "0197000022", "MTN", key));

            assertThat(responses).filteredOn(r -> r.status() == 202).hasSize(1);
            assertThat(responses).allMatch(r -> r.status() == 202 || r.status() == 200);
            assertThat(responses).extracting(r -> id(r.body())).containsOnly(id(responses.get(0).body()));
            assertThat(payments.findByRequestIdOrderByCreatedAtDesc(requestId)).hasSize(1);
            assertThat(debitsRequested(requestId)).isEqualTo(1);
        }

        @Test
        void concurrent_requests_without_key_single_debit() throws Exception {
            UUID requestId = id(createRequest("alice", "CRIMINAL_RECORD", 1));

            List<Response> responses = inBurst(20, () -> start("alice", requestId, "0197000023", "MOOV", null));

            assertThat(responses).filteredOn(r -> r.status() == 202).hasSize(1);
            assertThat(responses).filteredOn(r -> r.status() == 409).hasSize(19);
            assertThat(payments.findByRequestIdOrderByCreatedAtDesc(requestId)).hasSize(1);
            assertThat(debitsRequested(requestId)).isEqualTo(1);
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Résultats de l'opérateur")
    class OperatorResults {

        @Test
        void unsigned_or_badly_signed_result_is_ignored() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            UUID paymentId = id(start("alice", requestId, "0197000030", "MTN", null).body());
            String success = message(paymentId, "MTN", "SUCCESS", 1100);

            assertThat(callback("MTN", success, null).status()).isEqualTo(401);
            assertThat(callback("MTN", success, "deadbeef").status()).isEqualTo(401);
            assertThat(callback("MTN", success, HmacSignature.sign("mauvais-secret", bytes(success))).status()).isEqualTo(401);
            // Signé avec le secret d'un autre opérateur
            assertThat(callback("MTN", success, HmacSignature.sign(SECRET_MOOV, bytes(success))).status()).isEqualTo(401);
            // Corps modifié après signature
            String signature = HmacSignature.sign(SECRET_MTN, bytes(message(paymentId, "MTN", "FAILURE", 1100)));
            assertThat(callback("MTN", success, signature).status()).isEqualTo(401);

            assertThat(paymentStatus("alice", paymentId)).isEqualTo("PENDING");
        }

        @Test
        void authentic_result_is_applied() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            UUID paymentId = id(start("alice", requestId, "0197000031", "MTN", null).body());
            String success = message(paymentId, "MTN", "SUCCESS", 1100);

            Response r = callback("MTN", success, HmacSignature.sign(SECRET_MTN, bytes(success)));

            assertThat(r.status()).isEqualTo(200);
            assertThat(paymentStatus("alice", paymentId)).isEqualTo("SUCCEEDED");
        }

        @Test
        void operator_cannot_answer_for_another_operators_payment() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            UUID paymentId = id(start("alice", requestId, "0197000032", "MTN", null).body());
            String success = message(paymentId, "MOOV", "SUCCESS", 1100);

            Response r = callback("MOOV", success, HmacSignature.sign(SECRET_MOOV, bytes(success)));

            assertThat(r.status()).isEqualTo(404);
            assertThat(paymentStatus("alice", paymentId)).isEqualTo("PENDING");
        }

        @Test
        void mismatched_amount_is_rejected() {
            UUID requestId = id(createRequest("alice", "CRIMINAL_RECORD", 2));
            UUID paymentId = id(start("alice", requestId, "0197000033", "MTN", null).body());
            String tampered = message(paymentId, "MTN", "SUCCESS", 100);

            Response r = callback("MTN", tampered, HmacSignature.sign(SECRET_MTN, bytes(tampered)));

            assertThat(r.status()).isEqualTo(422);
            assertThat(r.code()).isEqualTo("AMOUNT_MISMATCH");
            assertThat(paymentStatus("alice", paymentId)).isEqualTo("PENDING");
        }

        @Test
        void succeeded_payment_never_changes_state() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            UUID paymentId = id(start("alice", requestId, "0197000034", "MTN", null).body());
            simulator.sendResult(paymentId.toString(), true, null);

            // L'opérateur renvoie le même résultat
            assertThat(simulator.resendResult(paymentId.toString())).isTrue();
            // Puis un résultat contradictoire, pourtant authentique
            String failure = message(paymentId, "MTN", "FAILURE", 1100);
            Response r = callback("MTN", failure, HmacSignature.sign(SECRET_MTN, bytes(failure)));

            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body().get("applied").asBoolean()).isFalse();
            assertThat(paymentStatus("alice", paymentId)).isEqualTo("SUCCEEDED");
            assertThat(request("alice", requestId).get("status").asText()).isEqualTo("PAID");
        }

        @Test
        void failed_payment_never_changes_state() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            UUID paymentId = id(start("alice", requestId, "0197000035", "MTN", null).body());
            simulator.sendResult(paymentId.toString(), false, "Refusé par l'abonné");

            String success = message(paymentId, "MTN", "SUCCESS", 1100);
            callback("MTN", success, HmacSignature.sign(SECRET_MTN, bytes(success)));

            assertThat(paymentStatus("alice", paymentId)).isEqualTo("FAILED");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Résultat qui n'arrive jamais")
    class MissingResult {

        @Test
        void pending_debit_is_cancelled_then_request_is_payable_again() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            UUID paymentId = id(start("alice", requestId, "0197000099", "MTN", null).body());

            reconciliation.reconcile(Instant.now().plus(Duration.ofMinutes(3)));
            assertThat(paymentStatus("alice", paymentId)).isEqualTo("PENDING");

            reconciliation.reconcile(Instant.now().plus(Duration.ofMinutes(11)));
            assertThat(paymentStatus("alice", paymentId)).isEqualTo("EXPIRED");
            assertThat(request("alice", requestId).get("status").asText()).isEqualTo("UNPAID");

            // Le débit annulé ne peut plus aboutir chez l'opérateur
            assertThat(simulator.sendResult(paymentId.toString(), true, null)).isFalse();
            assertThat(start("alice", requestId, "0197000098", "MTN", null).status()).isEqualTo(202);
        }

        @Test
        void lost_callback_is_recovered_by_querying_operator() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            UUID paymentId = id(start("alice", requestId, "0197000040", "MOOV", null).body());
            simulator.setOutcomeWithoutCallback(paymentId.toString(), true, null);

            reconciliation.reconcile(Instant.now().plus(Duration.ofMinutes(3)));

            assertThat(paymentStatus("alice", paymentId)).isEqualTo("SUCCEEDED");
        }

        @Test
        void lost_acknowledgement_does_not_mean_failure() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            simulator.simulateLostAcknowledgements(true);

            Response r = start("alice", requestId, "0197000041", "CELTIIS", null);

            assertThat(r.status()).isEqualTo(202);
            assertThat(r.body().get("status").asText()).isEqualTo("PENDING");
            // Pas de second débit possible en attendant
            assertThat(start("alice", requestId, "0197000041", "CELTIIS", null).status()).isEqualTo(409);

            simulator.sendResult(id(r.body()).toString(), true, null);
            assertThat(paymentStatus("alice", id(r.body()))).isEqualTo("SUCCEEDED");
            assertThat(debitsRequested(requestId)).isEqualTo(1);
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Chaque usager n'agit que sur ses propres données")
    class Isolation {

        @Test
        void user_cannot_see_or_pay_another_users_requests() {
            UUID requestId = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            UUID paymentId = id(start("alice", requestId, "0197000050", "MTN", null).body());

            assertThat(call(HttpMethod.GET, "/api/document-requests/" + requestId, "bob", null, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.GET, "/api/payments/" + paymentId, "bob", null, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.GET, "/api/document-requests/" + requestId + "/payments", "bob", null, null).status())
                    .isEqualTo(404);
            assertThat(call(HttpMethod.GET, "/api/document-requests", "bob", null, null).body().toString())
                    .doesNotContain(requestId.toString());

            UUID otherRequest = id(createRequest("alice", "BIRTH_CERTIFICATE", 1));
            assertThat(start("bob", otherRequest, "0197000051", "MTN", null).status()).isEqualTo(404);
            assertThat(debitsRequested(otherRequest)).isZero();
        }

        @Test
        void call_without_identification_is_rejected() {
            assertThat(call(HttpMethod.GET, "/api/document-requests", null, null, null).status()).isEqualTo(401);
            assertThat(call(HttpMethod.POST, "/api/document-requests", null, null,
                    "{\"documentType\":\"BIRTH_CERTIFICATE\",\"copies\":1}").status()).isEqualTo(401);
        }
    }

    // =====================================================================================
    // Outils

    record Response(int status, JsonNode body) {
        String code() {
            return body.path("code").asText(null);
        }
    }

    private JsonNode createRequest(String user, String type, int copies) {
        Response r = call(HttpMethod.POST, "/api/document-requests", user, null,
                "{\"documentType\":\"" + type + "\",\"copies\":" + copies + "}");
        assertThat(r.status()).isEqualTo(201);
        return r.body();
    }

    private JsonNode request(String user, UUID requestId) {
        return call(HttpMethod.GET, "/api/document-requests/" + requestId, user, null, null).body();
    }

    private Response start(String user, UUID requestId, String phoneNumber, String operator, String key) {
        return call(HttpMethod.POST, "/api/document-requests/" + requestId + "/payments", user, key,
                "{\"phoneNumber\":\"" + phoneNumber + "\",\"operator\":\"" + operator + "\"}");
    }

    private JsonNode payment(String user, UUID paymentId) {
        Response r = call(HttpMethod.GET, "/api/payments/" + paymentId, user, null, null);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    private String paymentStatus(String user, UUID paymentId) {
        return payment(user, paymentId).get("status").asText();
    }

    private Response callback(String operator, String body, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) {
            headers.set("X-Signature", signature);
        }
        return execute(HttpMethod.POST, "/api/operators/" + operator + "/results",
                new HttpEntity<>(bytes(body), headers));
    }

    private String message(UUID paymentId, String operator, String status, long amount) {
        return "{\"reference\":\"" + paymentId + "\",\"operatorReference\":\"OP-TEST\",\"operator\":\""
                + operator + "\",\"status\":\"" + status + "\",\"amount\":" + amount + "}";
    }

    private Response call(HttpMethod method, String url, String user, String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            headers.set("X-User-Id", user);
        }
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return execute(method, url, new HttpEntity<>(body, headers));
    }

    private Response execute(HttpMethod method, String url, HttpEntity<?> httpRequest) {
        ResponseEntity<String> r = http.exchange(url, method, httpRequest, String.class);
        try {
            JsonNode body = r.getBody() == null ? json.createObjectNode() : json.readTree(r.getBody());
            return new Response(r.getStatusCode().value(), body);
        } catch (Exception e) {
            throw new IllegalStateException("Réponse non JSON : " + r.getBody(), e);
        }
    }

    /** Nombre total de demandes de débit reçues par l'opérateur pour une demande d'acte. */
    private int debitsRequested(UUID requestId) {
        return payments.findByRequestIdOrderByCreatedAtDesc(requestId).stream()
                .map(Payment::getId)
                .mapToInt(id -> simulator.debitRequestCount(id.toString()))
                .sum();
    }

    /** Lance n requêtes au même instant (barrière de départ commune). */
    private List<Response> inBurst(int n, Callable<Response> httpRequest) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return httpRequest.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> f : futures) {
                responses.add(f.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asText());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
