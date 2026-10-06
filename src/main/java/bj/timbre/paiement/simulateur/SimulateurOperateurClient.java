package bj.timbre.paiement.simulateur;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import bj.timbre.paiement.operateur.AccuseReception;
import bj.timbre.paiement.operateur.ConsultationOperateur;
import bj.timbre.paiement.operateur.DemandeDebit;
import bj.timbre.paiement.operateur.OperateurClient;
import bj.timbre.paiement.paiement.Operateur;

/**
 * Branche le service sur le simulateur. Les demandes de débit, consultations et
 * annulations sont de simples appels en mémoire ; seul le résultat revient par HTTP
 * signé, comme le ferait un vrai opérateur.
 */
@Component
@ConditionalOnProperty(name = "simulateur.actif", havingValue = "true")
public class SimulateurOperateurClient implements OperateurClient {

    private final SimulateurOperateur simulateur;

    public SimulateurOperateurClient(SimulateurOperateur simulateur) {
        this.simulateur = simulateur;
    }

    @Override
    public AccuseReception demanderDebit(DemandeDebit demande) {
        return simulateur.recevoirDemandeDebit(demande);
    }

    @Override
    public ConsultationOperateur consulter(Operateur operateur, String reference) {
        return simulateur.consulter(reference);
    }

    @Override
    public ConsultationOperateur annuler(Operateur operateur, String reference) {
        return simulateur.annuler(reference);
    }
}
