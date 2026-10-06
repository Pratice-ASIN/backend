package bj.timbre.paiement;

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

import bj.timbre.paiement.operateur.SignatureHmac;
import bj.timbre.paiement.paiement.Paiement;
import bj.timbre.paiement.paiement.PaiementRepository;
import bj.timbre.paiement.paiement.ReconciliationService;
import bj.timbre.paiement.simulateur.SimulateurOperateur;

/**
 * Tests de bout en bout des cas critiques : vrai serveur HTTP, vraie base (H2),
 * simulateur en mode manuel pour maîtriser le moment où l'opérateur répond.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "simulateur.mode=manuel",
        "paiement.reconciliation.actif=false"
})
class PaiementIntegrationTest {

    private static final String SECRET_MTN = "secret-mtn-a-changer";
    private static final String SECRET_MOOV = "secret-moov-a-changer";

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;
    @Autowired
    SimulateurOperateur simulateur;
    @Autowired
    PaiementRepository paiements;
    @Autowired
    ReconciliationService reconciliation;

    @AfterEach
    void retablirSimulateur() {
        simulateur.simulerPerteDesAccuses(false);
    }

    // =====================================================================================
    @Nested
    @DisplayName("Montant et validations")
    class MontantEtValidations {

        @Test
        void le_montant_est_calcule_par_le_service() {
            assertThat(creerDemande("alice", "CASIER_JUDICIAIRE", 3).get("montantAPayer").asLong()).isEqualTo(4600);
            assertThat(creerDemande("alice", "ACTE_NAISSANCE", 1).get("montantAPayer").asLong()).isEqualTo(1100);
            JsonNode residence = creerDemande("alice", "CERTIFICAT_RESIDENCE", 2);
            assertThat(residence.get("montantAPayer").asLong()).isEqualTo(1100);
            assertThat(residence.get("statut").asText()).isEqualTo("A_PAYER");
        }

        @Test
        void un_montant_fourni_par_l_usager_est_refuse() {
            Reponse demande = appeler(HttpMethod.POST, "/api/demandes", "alice", null,
                    "{\"typeActe\":\"ACTE_NAISSANCE\",\"nombreCopies\":1,\"montant\":5}");
            assertThat(demande.statut()).isEqualTo(400);

            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            Reponse paiement = appeler(HttpMethod.POST, "/api/demandes/" + demandeId + "/paiements", "alice", null,
                    "{\"telephone\":\"0197000001\",\"operateur\":\"MTN\",\"montant\":5}");
            assertThat(paiement.statut()).isEqualTo(400);
            assertThat(debitsDemandes(demandeId)).isZero();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0197", "0297123456", "01971234567", "01ABCDEFGH", "+22901971234", "97123456"})
        void aucun_debit_pour_un_numero_invalide(String telephone) {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));

            Reponse r = lancer("alice", demandeId, telephone, "MTN", null);

            assertThat(r.statut()).isEqualTo(400);
            assertThat(r.code()).isEqualTo("TELEPHONE_INVALIDE");
            assertThat(paiements.findByDemandeIdOrderByCreeLeDesc(demandeId)).isEmpty();
            assertThat(simulateur.lister()).noneMatch(t -> telephone.equals(t.get("telephone")));
        }

        @Test
        void operateur_inconnu_refuse() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            assertThat(lancer("alice", demandeId, "0197000002", "ORANGE", null).statut()).isEqualTo(400);
            assertThat(paiements.findByDemandeIdOrderByCreeLeDesc(demandeId)).isEmpty();
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Cycle de vie du paiement")
    class CycleDeVie {

        @Test
        void parcours_nominal_jusqu_au_succes() {
            UUID demandeId = id(creerDemande("alice", "CASIER_JUDICIAIRE", 2));

            Reponse lancement = lancer("alice", demandeId, "0197000010", "MTN", null);
            assertThat(lancement.statut()).isEqualTo(202);
            assertThat(lancement.corps().get("statut").asText()).isEqualTo("EN_COURS");
            assertThat(lancement.corps().get("montant").asLong()).isEqualTo(3100);
            assertThat(demande("alice", demandeId).get("statut").asText()).isEqualTo("PAIEMENT_EN_COURS");

            UUID paiementId = id(lancement.corps());
            assertThat(simulateur.transmettreResultat(paiementId.toString(), true, null)).isTrue();

            assertThat(statutPaiement("alice", paiementId)).isEqualTo("REUSSI");
            assertThat(demande("alice", demandeId).get("statut").asText()).isEqualTo("PAYEE");
            assertThat(debitsDemandes(demandeId)).isEqualTo(1);
        }

        @Test
        void une_demande_en_cours_ou_payee_ne_peut_pas_etre_payee_une_seconde_fois() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            UUID paiementId = id(lancer("alice", demandeId, "0197000011", "MTN", null).corps());

            Reponse pendant = lancer("alice", demandeId, "0197000011", "MOOV", null);
            assertThat(pendant.statut()).isEqualTo(409);
            assertThat(pendant.code()).isEqualTo("PAIEMENT_EN_COURS");

            simulateur.transmettreResultat(paiementId.toString(), true, null);

            Reponse apres = lancer("alice", demandeId, "0197000011", "MTN", null);
            assertThat(apres.statut()).isEqualTo(409);
            assertThat(apres.code()).isEqualTo("DEMANDE_DEJA_PAYEE");
            assertThat(debitsDemandes(demandeId)).isEqualTo(1);
        }

        @Test
        void apres_un_echec_l_usager_peut_reessayer() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            UUID premier = id(lancer("alice", demandeId, "0197000012", "MOOV", null).corps());
            simulateur.transmettreResultat(premier.toString(), false, "Solde insuffisant");

            JsonNode echec = paiement("alice", premier);
            assertThat(echec.get("statut").asText()).isEqualTo("ECHOUE");
            assertThat(echec.get("motif").asText()).isEqualTo("Solde insuffisant");
            assertThat(demande("alice", demandeId).get("statut").asText()).isEqualTo("A_PAYER");

            Reponse second = lancer("alice", demandeId, "0197000012", "MOOV", null);
            assertThat(second.statut()).isEqualTo(202);
            simulateur.transmettreResultat(id(second.corps()).toString(), true, null);

            assertThat(demande("alice", demandeId).get("statut").asText()).isEqualTo("PAYEE");
            assertThat(statutPaiement("alice", premier)).isEqualTo("ECHOUE");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Un seul débit")
    class UnSeulDebit {

        @Test
        void une_requete_envoyee_deux_fois_ne_provoque_qu_un_debit() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            String cle = UUID.randomUUID().toString();

            Reponse premiere = lancer("alice", demandeId, "0197000020", "MTN", cle);
            Reponse rejeu = lancer("alice", demandeId, "0197000020", "MTN", cle);

            assertThat(premiere.statut()).isEqualTo(202);
            assertThat(rejeu.statut()).isEqualTo(200);
            assertThat(id(rejeu.corps())).isEqualTo(id(premiere.corps()));
            assertThat(debitsDemandes(demandeId)).isEqualTo(1);
        }

        @Test
        void une_cle_reutilisee_pour_une_autre_requete_est_refusee() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            String cle = UUID.randomUUID().toString();
            lancer("alice", demandeId, "0197000021", "MTN", cle);

            Reponse autre = lancer("alice", demandeId, "0197999999", "MTN", cle);
            assertThat(autre.statut()).isEqualTo(422);
            assertThat(debitsDemandes(demandeId)).isEqualTo(1);
        }

        @Test
        void requetes_identiques_simultanees_un_seul_debit() throws Exception {
            UUID demandeId = id(creerDemande("alice", "CASIER_JUDICIAIRE", 1));
            String cle = UUID.randomUUID().toString();

            List<Reponse> reponses = enRafale(20, () -> lancer("alice", demandeId, "0197000022", "MTN", cle));

            assertThat(reponses).filteredOn(r -> r.statut() == 202).hasSize(1);
            assertThat(reponses).allMatch(r -> r.statut() == 202 || r.statut() == 200);
            assertThat(reponses).extracting(r -> id(r.corps())).containsOnly(id(reponses.get(0).corps()));
            assertThat(paiements.findByDemandeIdOrderByCreeLeDesc(demandeId)).hasSize(1);
            assertThat(debitsDemandes(demandeId)).isEqualTo(1);
        }

        @Test
        void requetes_simultanees_sans_cle_un_seul_debit() throws Exception {
            UUID demandeId = id(creerDemande("alice", "CASIER_JUDICIAIRE", 1));

            List<Reponse> reponses = enRafale(20, () -> lancer("alice", demandeId, "0197000023", "MOOV", null));

            assertThat(reponses).filteredOn(r -> r.statut() == 202).hasSize(1);
            assertThat(reponses).filteredOn(r -> r.statut() == 409).hasSize(19);
            assertThat(paiements.findByDemandeIdOrderByCreeLeDesc(demandeId)).hasSize(1);
            assertThat(debitsDemandes(demandeId)).isEqualTo(1);
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Résultats de l'opérateur")
    class ResultatsOperateur {

        @Test
        void un_resultat_non_signe_ou_mal_signe_est_ignore() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            UUID paiementId = id(lancer("alice", demandeId, "0197000030", "MTN", null).corps());
            String succes = message(paiementId, "MTN", "SUCCES", 1100);

            assertThat(rappel("MTN", succes, null).statut()).isEqualTo(401);
            assertThat(rappel("MTN", succes, "deadbeef").statut()).isEqualTo(401);
            assertThat(rappel("MTN", succes, SignatureHmac.signer("mauvais-secret", octets(succes))).statut()).isEqualTo(401);
            // Signé avec le secret d'un autre opérateur
            assertThat(rappel("MTN", succes, SignatureHmac.signer(SECRET_MOOV, octets(succes))).statut()).isEqualTo(401);
            // Corps modifié après signature
            String signature = SignatureHmac.signer(SECRET_MTN, octets(message(paiementId, "MTN", "ECHEC", 1100)));
            assertThat(rappel("MTN", succes, signature).statut()).isEqualTo(401);

            assertThat(statutPaiement("alice", paiementId)).isEqualTo("EN_COURS");
        }

        @Test
        void un_resultat_authentique_est_pris_en_compte() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            UUID paiementId = id(lancer("alice", demandeId, "0197000031", "MTN", null).corps());
            String succes = message(paiementId, "MTN", "SUCCES", 1100);

            Reponse r = rappel("MTN", succes, SignatureHmac.signer(SECRET_MTN, octets(succes)));

            assertThat(r.statut()).isEqualTo(200);
            assertThat(statutPaiement("alice", paiementId)).isEqualTo("REUSSI");
        }

        @Test
        void un_operateur_ne_peut_pas_repondre_pour_le_paiement_d_un_autre() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            UUID paiementId = id(lancer("alice", demandeId, "0197000032", "MTN", null).corps());
            String succes = message(paiementId, "MOOV", "SUCCES", 1100);

            Reponse r = rappel("MOOV", succes, SignatureHmac.signer(SECRET_MOOV, octets(succes)));

            assertThat(r.statut()).isEqualTo(404);
            assertThat(statutPaiement("alice", paiementId)).isEqualTo("EN_COURS");
        }

        @Test
        void un_montant_incoherent_est_rejete() {
            UUID demandeId = id(creerDemande("alice", "CASIER_JUDICIAIRE", 2));
            UUID paiementId = id(lancer("alice", demandeId, "0197000033", "MTN", null).corps());
            String falsifie = message(paiementId, "MTN", "SUCCES", 100);

            Reponse r = rappel("MTN", falsifie, SignatureHmac.signer(SECRET_MTN, octets(falsifie)));

            assertThat(r.statut()).isEqualTo(422);
            assertThat(r.code()).isEqualTo("MONTANT_INCOHERENT");
            assertThat(statutPaiement("alice", paiementId)).isEqualTo("EN_COURS");
        }

        @Test
        void un_paiement_reussi_ne_change_plus_d_etat() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            UUID paiementId = id(lancer("alice", demandeId, "0197000034", "MTN", null).corps());
            simulateur.transmettreResultat(paiementId.toString(), true, null);

            // L'opérateur renvoie le même résultat
            assertThat(simulateur.renvoyerResultat(paiementId.toString())).isTrue();
            // Puis un résultat contradictoire, pourtant authentique
            String echec = message(paiementId, "MTN", "ECHEC", 1100);
            Reponse r = rappel("MTN", echec, SignatureHmac.signer(SECRET_MTN, octets(echec)));

            assertThat(r.statut()).isEqualTo(200);
            assertThat(r.corps().get("prisEnCompte").asBoolean()).isFalse();
            assertThat(statutPaiement("alice", paiementId)).isEqualTo("REUSSI");
            assertThat(demande("alice", demandeId).get("statut").asText()).isEqualTo("PAYEE");
        }

        @Test
        void un_paiement_echoue_ne_change_plus_d_etat() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            UUID paiementId = id(lancer("alice", demandeId, "0197000035", "MTN", null).corps());
            simulateur.transmettreResultat(paiementId.toString(), false, "Refusé par l'abonné");

            String succes = message(paiementId, "MTN", "SUCCES", 1100);
            rappel("MTN", succes, SignatureHmac.signer(SECRET_MTN, octets(succes)));

            assertThat(statutPaiement("alice", paiementId)).isEqualTo("ECHOUE");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Résultat qui n'arrive jamais")
    class ResultatAbsent {

        @Test
        void le_debit_en_attente_est_annule_puis_la_demande_redevient_payable() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            UUID paiementId = id(lancer("alice", demandeId, "0197000099", "MTN", null).corps());

            reconciliation.reconcilier(Instant.now().plus(Duration.ofMinutes(3)));
            assertThat(statutPaiement("alice", paiementId)).isEqualTo("EN_COURS");

            reconciliation.reconcilier(Instant.now().plus(Duration.ofMinutes(11)));
            assertThat(statutPaiement("alice", paiementId)).isEqualTo("EXPIRE");
            assertThat(demande("alice", demandeId).get("statut").asText()).isEqualTo("A_PAYER");

            // Le débit annulé ne peut plus aboutir chez l'opérateur
            assertThat(simulateur.transmettreResultat(paiementId.toString(), true, null)).isFalse();
            assertThat(lancer("alice", demandeId, "0197000098", "MTN", null).statut()).isEqualTo(202);
        }

        @Test
        void un_rappel_perdu_est_rattrape_en_interrogeant_l_operateur() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            UUID paiementId = id(lancer("alice", demandeId, "0197000040", "MOOV", null).corps());
            simulateur.fixerIssueSansRappel(paiementId.toString(), true, null);

            reconciliation.reconcilier(Instant.now().plus(Duration.ofMinutes(3)));

            assertThat(statutPaiement("alice", paiementId)).isEqualTo("REUSSI");
        }

        @Test
        void un_accuse_perdu_ne_fait_pas_conclure_a_un_echec() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            simulateur.simulerPerteDesAccuses(true);

            Reponse r = lancer("alice", demandeId, "0197000041", "CELTIIS", null);

            assertThat(r.statut()).isEqualTo(202);
            assertThat(r.corps().get("statut").asText()).isEqualTo("EN_COURS");
            // Pas de second débit possible en attendant
            assertThat(lancer("alice", demandeId, "0197000041", "CELTIIS", null).statut()).isEqualTo(409);

            simulateur.transmettreResultat(id(r.corps()).toString(), true, null);
            assertThat(statutPaiement("alice", id(r.corps()))).isEqualTo("REUSSI");
            assertThat(debitsDemandes(demandeId)).isEqualTo(1);
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Chaque usager n'agit que sur ses propres données")
    class Isolation {

        @Test
        void un_usager_ne_voit_ni_ne_paie_les_demandes_d_un_autre() {
            UUID demandeId = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            UUID paiementId = id(lancer("alice", demandeId, "0197000050", "MTN", null).corps());

            assertThat(appeler(HttpMethod.GET, "/api/demandes/" + demandeId, "bob", null, null).statut()).isEqualTo(404);
            assertThat(appeler(HttpMethod.GET, "/api/paiements/" + paiementId, "bob", null, null).statut()).isEqualTo(404);
            assertThat(appeler(HttpMethod.GET, "/api/demandes/" + demandeId + "/paiements", "bob", null, null).statut())
                    .isEqualTo(404);
            assertThat(appeler(HttpMethod.GET, "/api/demandes", "bob", null, null).corps().toString())
                    .doesNotContain(demandeId.toString());

            UUID autreDemande = id(creerDemande("alice", "ACTE_NAISSANCE", 1));
            assertThat(lancer("bob", autreDemande, "0197000051", "MTN", null).statut()).isEqualTo(404);
            assertThat(debitsDemandes(autreDemande)).isZero();
        }

        @Test
        void un_appel_sans_identification_est_refuse() {
            assertThat(appeler(HttpMethod.GET, "/api/demandes", null, null, null).statut()).isEqualTo(401);
            assertThat(appeler(HttpMethod.POST, "/api/demandes", null, null,
                    "{\"typeActe\":\"ACTE_NAISSANCE\",\"nombreCopies\":1}").statut()).isEqualTo(401);
        }
    }

    // =====================================================================================
    // Outils

    record Reponse(int statut, JsonNode corps) {
        String code() {
            return corps.path("code").asText(null);
        }
    }

    private JsonNode creerDemande(String usager, String type, int copies) {
        Reponse r = appeler(HttpMethod.POST, "/api/demandes", usager, null,
                "{\"typeActe\":\"" + type + "\",\"nombreCopies\":" + copies + "}");
        assertThat(r.statut()).isEqualTo(201);
        return r.corps();
    }

    private JsonNode demande(String usager, UUID demandeId) {
        return appeler(HttpMethod.GET, "/api/demandes/" + demandeId, usager, null, null).corps();
    }

    private Reponse lancer(String usager, UUID demandeId, String telephone, String operateur, String cle) {
        return appeler(HttpMethod.POST, "/api/demandes/" + demandeId + "/paiements", usager, cle,
                "{\"telephone\":\"" + telephone + "\",\"operateur\":\"" + operateur + "\"}");
    }

    private JsonNode paiement(String usager, UUID paiementId) {
        Reponse r = appeler(HttpMethod.GET, "/api/paiements/" + paiementId, usager, null, null);
        assertThat(r.statut()).isEqualTo(200);
        return r.corps();
    }

    private String statutPaiement(String usager, UUID paiementId) {
        return paiement(usager, paiementId).get("statut").asText();
    }

    private Reponse rappel(String operateur, String corps, String signature) {
        HttpHeaders entetes = new HttpHeaders();
        entetes.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) {
            entetes.set("X-Signature", signature);
        }
        return executer(HttpMethod.POST, "/api/operateurs/" + operateur + "/resultats",
                new HttpEntity<>(octets(corps), entetes));
    }

    private String message(UUID paiementId, String operateur, String statut, long montant) {
        return "{\"reference\":\"" + paiementId + "\",\"referenceOperateur\":\"OP-TEST\",\"operateur\":\""
                + operateur + "\",\"statut\":\"" + statut + "\",\"montant\":" + montant + "}";
    }

    private Reponse appeler(HttpMethod methode, String url, String usager, String cle, String corps) {
        HttpHeaders entetes = new HttpHeaders();
        entetes.setContentType(MediaType.APPLICATION_JSON);
        if (usager != null) {
            entetes.set("X-Usager-Id", usager);
        }
        if (cle != null) {
            entetes.set("Idempotency-Key", cle);
        }
        return executer(methode, url, new HttpEntity<>(corps, entetes));
    }

    private Reponse executer(HttpMethod methode, String url, HttpEntity<?> requete) {
        ResponseEntity<String> r = http.exchange(url, methode, requete, String.class);
        try {
            JsonNode corps = r.getBody() == null ? json.createObjectNode() : json.readTree(r.getBody());
            return new Reponse(r.getStatusCode().value(), corps);
        } catch (Exception e) {
            throw new IllegalStateException("Réponse non JSON : " + r.getBody(), e);
        }
    }

    /** Nombre total de demandes de débit reçues par l'opérateur pour une demande d'acte. */
    private int debitsDemandes(UUID demandeId) {
        return paiements.findByDemandeIdOrderByCreeLeDesc(demandeId).stream()
                .map(Paiement::getId)
                .mapToInt(id -> simulateur.nombreDemandesDebitRecues(id.toString()))
                .sum();
    }

    /** Lance n requêtes au même instant (barrière de départ commune). */
    private List<Reponse> enRafale(int n, Callable<Reponse> requete) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch prets = new CountDownLatch(n);
            CountDownLatch depart = new CountDownLatch(1);
            List<Future<Reponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    prets.countDown();
                    depart.await();
                    return requete.call();
                }));
            }
            prets.await(10, TimeUnit.SECONDS);
            depart.countDown();
            List<Reponse> reponses = new ArrayList<>();
            for (Future<Reponse> f : futures) {
                reponses.add(f.get(60, TimeUnit.SECONDS));
            }
            return reponses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static UUID id(JsonNode noeud) {
        return UUID.fromString(noeud.get("id").asText());
    }

    private static byte[] octets(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
