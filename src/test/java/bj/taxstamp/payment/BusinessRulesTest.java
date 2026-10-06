package bj.taxstamp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import bj.taxstamp.payment.request.DocumentRequest;
import bj.taxstamp.payment.request.DocumentType;
import bj.taxstamp.payment.operator.HmacSignature;
import bj.taxstamp.payment.payment.MobileOperator;
import bj.taxstamp.payment.payment.Payment;
import bj.taxstamp.payment.payment.PaymentStatus;
import bj.taxstamp.payment.payment.PhoneNumber;

/** Tests unitaires des règles de gestion, sans Spring. */
class BusinessRulesTest {

    private static final DocumentType BIRTH_CERTIFICATE = new DocumentType("BIRTH_CERTIFICATE", "Acte de naissance", 1000);
    private static final DocumentType CRIMINAL_RECORD = new DocumentType("CRIMINAL_RECORD", "Casier judiciaire", 1500);

    @ParameterizedTest(name = "{1} x {0} FCFA + frais = {2} FCFA")
    @CsvSource({
            "1000, 1, 1100",
            "1000, 3, 3100",
            "1500, 1, 1600",
            "1500, 2, 3100",
            " 500, 1, 600",
            " 500, 4, 2100"
    })
    void amount_is_unit_price_times_copies_plus_fee(long unitPrice, int copies, long expected) {
        assertThat(new DocumentType("X", "x", unitPrice).amountDue(copies)).isEqualTo(expected);
    }

    @Test
    void zero_copies_forbidden() {
        assertThatThrownBy(() -> BIRTH_CERTIFICATE.amountDue(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negative_unit_price_forbidden() {
        assertThatThrownBy(() -> new DocumentType("X", "x", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0197123456", "0161000000", "0100000000"})
    void valid_phone_numbers(String number) {
        assertThat(PhoneNumber.isValid(number)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "97123456", "0297123456", "019712345", "01971234567", "01 9712345", "+2290197123"})
    void invalid_phone_numbers(String number) {
        assertThat(PhoneNumber.isValid(number)).isFalse();
    }

    @Test
    void null_phone_number_is_invalid() {
        assertThat(PhoneNumber.isValid(null)).isFalse();
    }

    @Test
    void signature_valid_only_with_right_secret_and_exact_body() {
        byte[] body = "{\"status\":\"SUCCESS\",\"amount\":1100}".getBytes(StandardCharsets.UTF_8);
        String signature = HmacSignature.sign("secret", body);

        assertThat(HmacSignature.verify("secret", body, signature)).isTrue();
        assertThat(HmacSignature.verify("secret", body, signature.toUpperCase())).isTrue();
        assertThat(HmacSignature.verify("autre", body, signature)).isFalse();
        assertThat(HmacSignature.verify("secret",
                "{\"status\":\"SUCCESS\",\"amount\":9100}".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(HmacSignature.verify("secret", body, null)).isFalse();
        assertThat(HmacSignature.verify("secret", body, "")).isFalse();
    }

    @Test
    void completed_payment_never_changes_state() {
        Payment p = newPayment();
        p.complete(PaymentStatus.SUCCEEDED, "OP-1", null, Instant.now());

        assertThatThrownBy(() -> p.complete(PaymentStatus.FAILED, "OP-1", "x", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void payment_amount_comes_from_the_request() {
        assertThat(newPayment().getAmount()).isEqualTo(3100);
    }

    private static Payment newPayment() {
        DocumentRequest request = DocumentRequest.create("alice", CRIMINAL_RECORD, 2, Instant.now());
        return Payment.create(request, "alice", "0197123456", MobileOperator.MTN, null, Instant.now());
    }
}
