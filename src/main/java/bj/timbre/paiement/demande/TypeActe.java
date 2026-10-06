package bj.timbre.paiement.demande;

/**
 * Types d'actes et tarifs unitaires du timbre fiscal (en FCFA).
 * Les montants sont des entiers : le FCFA n'a pas de subdivision, on évite
 * ainsi toute erreur d'arrondi liée aux nombres à virgule.
 */
public enum TypeActe {

    ACTE_NAISSANCE("Acte de naissance", 1000),
    CASIER_JUDICIAIRE("Casier judiciaire", 1500),
    CERTIFICAT_RESIDENCE("Certificat de résidence", 500);

    public static final long FRAIS_SERVICE = 100;

    private final String libelle;
    private final long tarifUnitaire;

    TypeActe(String libelle, long tarifUnitaire) {
        this.libelle = libelle;
        this.tarifUnitaire = tarifUnitaire;
    }

    /** Montant à payer = tarif unitaire × nombre de copies + frais de service. */
    public long montantAPayer(int nombreCopies) {
        if (nombreCopies < 1) {
            throw new IllegalArgumentException("Le nombre de copies doit être au moins 1");
        }
        return Math.addExact(Math.multiplyExact(tarifUnitaire, nombreCopies), FRAIS_SERVICE);
    }

    public String getLibelle() {
        return libelle;
    }

    public long getTarifUnitaire() {
        return tarifUnitaire;
    }
}
