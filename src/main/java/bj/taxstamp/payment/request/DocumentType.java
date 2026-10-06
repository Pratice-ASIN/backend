package bj.taxstamp.payment.request;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Type d'acte et tarif unitaire du timbre fiscal (en FCFA), stocké en base et
 * alimenté au démarrage par {@link DocumentTypeSeeder}.
 * Les montants sont des entiers : le FCFA n'a pas de subdivision, on évite
 * ainsi toute erreur d'arrondi liée aux nombres à virgule.
 */
@Entity
@Table(name = "document_type")
public class DocumentType {

    public static final long SERVICE_FEE = 100;

    /** Code stable exposé par l'API (ex. BIRTH_CERTIFICATE). */
    @Id
    @Column(name = "code", length = 40)
    private String code;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    protected DocumentType() {
    }

    public DocumentType(String code, String label, long unitPrice) {
        if (unitPrice < 0) {
            throw new IllegalArgumentException("Le tarif unitaire ne peut pas être négatif");
        }
        this.code = code;
        this.label = label;
        this.unitPrice = unitPrice;
    }

    /** Montant à payer = tarif unitaire × nombre de copies + frais de service. */
    public long amountDue(int copies) {
        if (copies < 1) {
            throw new IllegalArgumentException("Le nombre de copies doit être au moins 1");
        }
        return Math.addExact(Math.multiplyExact(unitPrice, copies), SERVICE_FEE);
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public long getUnitPrice() {
        return unitPrice;
    }
}
