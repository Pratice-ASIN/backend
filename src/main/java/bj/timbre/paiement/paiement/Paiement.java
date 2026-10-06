package bj.timbre.paiement.paiement;

import java.time.Instant;
import java.util.UUID;

import bj.timbre.paiement.demande.DemandeActe;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Une tentative de paiement d'une demande d'acte.
 *
 * <h2>Garantie "un seul paiement actif par demande"</h2>
 * La colonne {@code demande_verrou} vaut l'identifiant de la demande tant que le
 * paiement est EN_COURS ou REUSSI, et NULL après un échec ou une expiration.
 * Elle porte une contrainte UNIQUE : la base de données refuse donc, même sous
 * forte concurrence, un deuxième paiement en cours ou réussi pour la même demande
 * (les NULL ne sont pas comparés entre eux, ce qui autorise les nouvelles tentatives
 * après un échec). C'est l'équivalent portable d'un index unique partiel.
 */
@Entity
@Table(name = "paiement",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_paiement_demande_verrou", columnNames = "demande_verrou"),
                @UniqueConstraint(name = "uk_paiement_cle_idempotence", columnNames = {"usager_id", "cle_idempotence"})
        },
        indexes = {
                @Index(name = "idx_paiement_demande", columnList = "demande_id"),
                @Index(name = "idx_paiement_statut_creation", columnList = "statut, cree_le")
        })
public class Paiement {

    @Id
    private UUID id;

    @Column(name = "demande_id", nullable = false)
    private UUID demandeId;

    @Column(name = "usager_id", nullable = false, length = 64)
    private String usagerId;

    @Column(name = "telephone", nullable = false, length = 10)
    private String telephone;

    @Enumerated(EnumType.STRING)
    @Column(name = "operateur", nullable = false, length = 10)
    private Operateur operateur;

    /** Copié depuis la demande au lancement : jamais fourni par l'usager. */
    @Column(name = "montant", nullable = false)
    private long montant;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 10)
    private StatutPaiement statut;

    @Column(name = "demande_verrou")
    private UUID demandeVerrou;

    @Column(name = "cle_idempotence", length = 100)
    private String cleIdempotence;

    @Column(name = "reference_operateur", length = 100)
    private String referenceOperateur;

    @Column(name = "motif", length = 255)
    private String motif;

    @Column(name = "cree_le", nullable = false)
    private Instant creeLe;

    @Column(name = "maj_le", nullable = false)
    private Instant majLe;

    @Version
    private Long version;

    protected Paiement() {
    }

    public static Paiement nouveau(DemandeActe demande, String usagerId, String telephone, Operateur operateur,
                                   String cleIdempotence, Instant maintenant) {
        Paiement p = new Paiement();
        p.id = UUID.randomUUID();
        p.demandeId = demande.getId();
        p.usagerId = usagerId;
        p.telephone = telephone;
        p.operateur = operateur;
        p.montant = demande.getMontant();
        p.statut = StatutPaiement.EN_COURS;
        p.demandeVerrou = demande.getId();
        p.cleIdempotence = cleIdempotence;
        p.creeLe = maintenant;
        p.majLe = maintenant;
        return p;
    }

    /** Passage dans un état final. Interdit si le paiement est déjà finalisé. */
    public void finaliser(StatutPaiement nouveauStatut, String referenceOperateur, String motif, Instant maintenant) {
        if (statut.estFinal()) {
            throw new IllegalStateException("Paiement " + id + " déjà finalisé (" + statut + ")");
        }
        if (!nouveauStatut.estFinal()) {
            throw new IllegalArgumentException("Statut final attendu : " + nouveauStatut);
        }
        this.statut = nouveauStatut;
        this.motif = motif;
        if (this.referenceOperateur == null) {
            this.referenceOperateur = referenceOperateur;
        }
        // Après un échec, la demande redevient payable ; après une réussite, elle reste verrouillée.
        if (nouveauStatut != StatutPaiement.REUSSI) {
            this.demandeVerrou = null;
        }
        this.majLe = maintenant;
    }

    public void enregistrerReferenceOperateur(String reference, Instant maintenant) {
        if (this.referenceOperateur == null && reference != null) {
            this.referenceOperateur = reference;
            this.majLe = maintenant;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getDemandeId() {
        return demandeId;
    }

    public String getUsagerId() {
        return usagerId;
    }

    public String getTelephone() {
        return telephone;
    }

    public Operateur getOperateur() {
        return operateur;
    }

    public long getMontant() {
        return montant;
    }

    public StatutPaiement getStatut() {
        return statut;
    }

    public String getCleIdempotence() {
        return cleIdempotence;
    }

    public String getReferenceOperateur() {
        return referenceOperateur;
    }

    public String getMotif() {
        return motif;
    }

    public Instant getCreeLe() {
        return creeLe;
    }

    public Instant getMajLe() {
        return majLe;
    }
}
