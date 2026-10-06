package bj.timbre.paiement.demande;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "demande_acte", indexes = @Index(name = "idx_demande_usager", columnList = "usager_id"))
public class DemandeActe {

    @Id
    private UUID id;

    @Column(name = "usager_id", nullable = false, length = 64)
    private String usagerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type_acte", nullable = false, length = 40)
    private TypeActe typeActe;

    @Column(name = "nombre_copies", nullable = false)
    private int nombreCopies;

    /** Montant figé à la création, calculé par le service. */
    @Column(name = "montant", nullable = false)
    private long montant;

    @Column(name = "cree_le", nullable = false)
    private Instant creeLe;

    @Version
    private Long version;

    protected DemandeActe() {
    }

    public static DemandeActe creer(String usagerId, TypeActe typeActe, int nombreCopies, Instant maintenant) {
        DemandeActe d = new DemandeActe();
        d.id = UUID.randomUUID();
        d.usagerId = usagerId;
        d.typeActe = typeActe;
        d.nombreCopies = nombreCopies;
        d.montant = typeActe.montantAPayer(nombreCopies);
        d.creeLe = maintenant;
        return d;
    }

    public UUID getId() {
        return id;
    }

    public String getUsagerId() {
        return usagerId;
    }

    public TypeActe getTypeActe() {
        return typeActe;
    }

    public int getNombreCopies() {
        return nombreCopies;
    }

    public long getMontant() {
        return montant;
    }

    public Instant getCreeLe() {
        return creeLe;
    }
}
