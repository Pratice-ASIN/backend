package bj.taxstamp.payment.request;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRequestRepository extends JpaRepository<DocumentRequest, UUID> {

    /** Toujours filtrer par usager : une demande d'autrui est "introuvable". */
    Optional<DocumentRequest> findByIdAndUserId(UUID id, String userId);

    List<DocumentRequest> findByUserIdOrderByCreatedAtDesc(String userId);
}
