package bj.timbre.paiement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import bj.timbre.paiement.demande.DemandeActe;
import bj.timbre.paiement.demande.TypeActe;
import bj.timbre.paiement.operateur.SignatureHmac;
import bj.timbre.paiement.paiement.Operateur;
import bj.timbre.paiement.paiement.Paiement;
import bj.timbre.paiement.paiement.StatutPaiement;
import bj.timbre.paiement.paiement.Telephone;

/** Tests unitaires des règles de gestion, sans Spring. */
class ReglesMetierTest {

    @ParameterizedTest(name = "{0} x {1} = {2} FCFA")
    @CsvSource({
            "ACTE_NAISSANCE,       1, 1100",
            "ACTE_NAISSANCE,       3, 3100",
            "CASIER_JUDICIAIRE,    1, 1600",
            "CASIER_JUDICIAIRE,    2, 3100",
            "CERTIFICAT_RESIDENCE, 1, 600",
            "CERTIFICAT_RESIDENCE, 4, 2100"
    })
    void montant_tarif_fois_copies_plus_frais(TypeActe type, int copies, long attendu) {
        assertThat(type.montantAPayer(copies)).isEqualTo(attendu);
    }

    @Test
    void zero_copie_interdit() {
        assertThatThrownBy(() -> TypeActe.ACTE_NAISSANCE.montantAPayer(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0197123456", "0161000000", "0100000000"})
    void telephones_valides(String numero) {
        assertThat(Telephone.estValide(numero)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "97123456", "0297123456", "019712345", "01971234567", "01 9712345", "+2290197123"})
    void telephones_invalides(String numero) {
        assertThat(Telephone.estValide(numero)).isFalse();
    }

    @Test
    void telephone_null_invalide() {
        assertThat(Telephone.estValide(null)).isFalse();
    }

    @Test
    void signature_valide_seulement_avec_le_bon_secret_et_le_corps_exact() {
        byte[] corps = "{\"statut\":\"SUCCES\",\"montant\":1100}".getBytes(StandardCharsets.UTF_8);
        String signature = SignatureHmac.signer("secret", corps);

        assertThat(SignatureHmac.verifier("secret", corps, signature)).isTrue();
        assertThat(SignatureHmac.verifier("secret", corps, signature.toUpperCase())).isTrue();
        assertThat(SignatureHmac.verifier("autre", corps, signature)).isFalse();
        assertThat(SignatureHmac.verifier("secret",
                "{\"statut\":\"SUCCES\",\"montant\":9100}".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(SignatureHmac.verifier("secret", corps, null)).isFalse();
        assertThat(SignatureHmac.verifier("secret", corps, "")).isFalse();
    }

    @Test
    void un_paiement_finalise_ne_change_plus_d_etat() {
        Paiement p = nouveauPaiement();
        p.finaliser(StatutPaiement.REUSSI, "OP-1", null, Instant.now());

        assertThatThrownBy(() -> p.finaliser(StatutPaiement.ECHOUE, "OP-1", "x", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatut()).isEqualTo(StatutPaiement.REUSSI);
    }

    @Test
    void le_montant_du_paiement_vient_de_la_demande() {
        assertThat(nouveauPaiement().getMontant()).isEqualTo(3100);
    }

    private static Paiement nouveauPaiement() {
        DemandeActe demande = DemandeActe.creer("alice", TypeActe.CASIER_JUDICIAIRE, 2, Instant.now());
        return Paiement.nouveau(demande, "alice", "0197123456", Operateur.MTN, null, Instant.now());
    }
}
