package bj.taxstamp.payment.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Verrou ligne (SELECT ... FOR UPDATE) : sérialise les mises à jour concurrentes d'un paiement. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findForUpdate(@Param("id") UUID id);

    /** Paiement PENDING ou SUCCEEDED de la demande (au plus un, garanti par contrainte unique). */
    Optional<Payment> findByRequestLock(UUID requestId);

    Optional<Payment> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    Optional<Payment> findByIdAndUserId(UUID id, String userId);

    List<Payment> findByRequestIdOrderByCreatedAtDesc(UUID requestId);

    List<Payment> findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(PaymentStatus status, Instant avant);
}
