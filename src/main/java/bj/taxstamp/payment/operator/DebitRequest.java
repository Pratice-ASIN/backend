package bj.taxstamp.payment.operator;

import bj.taxstamp.payment.payment.MobileOperator;

public record DebitRequest(MobileOperator operator, String reference, String phoneNumber, long amount) {
}
