package bj.taxstamp.payment.request;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Insère au démarrage les types d'actes et leurs tarifs.
 * Idempotent : un acte déjà présent n'est pas modifié, pour ne pas écraser un
 * tarif mis à jour directement en base.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DocumentTypeSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentTypeSeeder.class);

    static final List<DocumentType> DEFAULTS = List.of(
            new DocumentType("BIRTH_CERTIFICATE", "Acte de naissance", 1000),
            new DocumentType("CRIMINAL_RECORD", "Casier judiciaire", 1500),
            new DocumentType("RESIDENCE_CERTIFICATE", "Certificat de résidence", 500));

    private final DocumentTypeRepository types;

    public DocumentTypeSeeder(DocumentTypeRepository types) {
        this.types = types;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<DocumentType> missing = DEFAULTS.stream()
                .filter(t -> !types.existsById(t.getCode()))
                .toList();
        types.saveAll(missing);
        log.info("Types d'actes : {} ajouté(s), {} déjà présent(s)", missing.size(), DEFAULTS.size() - missing.size());
    }
}
