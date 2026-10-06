package bj.taxstamp.payment.request;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, String> {

    List<DocumentType> findAllByOrderByLabelAsc();
}
