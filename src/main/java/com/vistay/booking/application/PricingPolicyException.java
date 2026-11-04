package com.vistay.booking.application;

public final class PricingPolicyException extends RuntimeException {
    private final String code;

    public PricingPolicyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
