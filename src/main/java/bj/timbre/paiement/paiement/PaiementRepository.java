package bj.timbre.paiement.paiement;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PaiementRepository extends JpaRepository<Paiement, UUID> {

    /** Verrou ligne (SELECT ... FOR UPDATE) : sérialise les mises à jour concurrentes d'un paiement. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Paiement p where p.id = :id")
    Optional<Paiement> findPourMiseAJour(@Param("id") UUID id);

    /** Paiement EN_COURS ou REUSSI de la demande (au plus un, garanti par contrainte unique). */
    Optional<Paiement> findByDemandeVerrou(UUID demandeId);

    Optional<Paiement> findByUsagerIdAndCleIdempotence(String usagerId, String cleIdempotence);

    Optional<Paiement> findByIdAndUsagerId(UUID id, String usagerId);

    List<Paiement> findByDemandeIdOrderByCreeLeDesc(UUID demandeId);

    List<Paiement> findTop100ByStatutAndCreeLeBeforeOrderByCreeLeAsc(StatutPaiement statut, Instant avant);
}
