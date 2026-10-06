package bj.taxstamp.payment.request;

/**
 * Types d'actes et tarifs unitaires du timbre fiscal (en FCFA).
 * Les montants sont des entiers : le FCFA n'a pas de subdivision, on évite
 * ainsi toute erreur d'arrondi liée aux nombres à virgule.
 */
public enum DocumentType {

    BIRTH_CERTIFICATE("Acte de naissance", 1000),
    CRIMINAL_RECORD("Casier judiciaire", 1500),
    RESIDENCE_CERTIFICATE("Certificat de résidence", 500);

    public static final long SERVICE_FEE = 100;

    private final String label;
    private final long unitPrice;

    DocumentType(String label, long unitPrice) {
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

    public String getLabel() {
        return label;
    }

    public long getUnitPrice() {
        return unitPrice;
    }
}
