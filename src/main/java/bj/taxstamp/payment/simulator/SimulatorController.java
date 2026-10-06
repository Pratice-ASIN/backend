package bj.taxstamp.payment.simulator;

import java.util.Collection;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bj.taxstamp.payment.common.BusinessError;

/** Pilotage du simulateur pour la démonstration. N'existe pas en production (simulator.enabled=false). */
@RestController
@RequestMapping("/simulator/transactions")
@ConditionalOnProperty(name = "simulator.enabled", havingValue = "true")
public class SimulatorController {

    private final OperatorSimulator simulator;

    public SimulatorController(OperatorSimulator simulator) {
        this.simulator = simulator;
    }

    public record ManualResult(boolean success, String reason) {
    }

    @GetMapping
    public Collection<Map<String, Object>> list() {
        return simulator.list();
    }

    @PostMapping("/{reference}/result")
    public Map<String, Object> send(@PathVariable String reference, @RequestBody ManualResult result) {
        if (!simulator.sendResult(reference, result.success(), result.reason())) {
            throw new BusinessError(HttpStatus.CONFLICT, "TRANSACTION_NOT_PENDING",
                    "Transaction inconnue ou déjà terminée");
        }
        return Map.of("reference", reference, "sent", true);
    }

    @PostMapping("/{reference}/resend")
    public Map<String, Object> resend(@PathVariable String reference) {
        if (!simulator.resendResult(reference)) {
            throw new BusinessError(HttpStatus.CONFLICT, "NO_RESULT", "Aucun résultat à renvoyer");
        }
        return Map.of("reference", reference, "resent", true);
    }
}
