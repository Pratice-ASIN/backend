package bj.timbre.paiement.demande;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DemandeActeRepository extends JpaRepository<DemandeActe, UUID> {

    /** Toujours filtrer par usager : une demande d'autrui est "introuvable". */
    Optional<DemandeActe> findByIdAndUsagerId(UUID id, String usagerId);

    List<DemandeActe> findByUsagerIdOrderByCreeLeDesc(String usagerId);
}
