package bj.taxstamp.payment.simulator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import bj.taxstamp.payment.operator.Acknowledgement;
import bj.taxstamp.payment.operator.OperatorStatusQuery;
import bj.taxstamp.payment.operator.DebitRequest;
import bj.taxstamp.payment.operator.OperatorClient;
import bj.taxstamp.payment.payment.MobileOperator;

/**
 * Branche le service sur le simulateur. Les demandes de débit, consultations et
 * annulations sont de simples appels en mémoire ; seul le résultat revient par HTTP
 * signé, comme le ferait un vrai opérateur.
 */
@Component
@ConditionalOnProperty(name = "simulator.enabled", havingValue = "true")
public class SimulatedOperatorClient implements OperatorClient {

    private final OperatorSimulator simulator;

    public SimulatedOperatorClient(OperatorSimulator simulator) {
        this.simulator = simulator;
    }

    @Override
    public Acknowledgement requestDebit(DebitRequest request) {
        return simulator.receiveDebitRequest(request);
    }

    @Override
    public OperatorStatusQuery queryStatus(MobileOperator operator, String reference) {
        return simulator.queryStatus(reference);
    }

    @Override
    public OperatorStatusQuery cancel(MobileOperator operator, String reference) {
        return simulator.cancel(reference);
    }
}
