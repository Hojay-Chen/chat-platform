package com.luxera.companion.contracts.application;

/**
 * LAP v1 — machine-readable failure detail.
 *
 * @param code    stable SCREAMING_SNAKE code ({@code NOT_A_PARTICIPANT}, {@code RISK_TOO_HIGH}, …);
 *                clients branch on this, never on the message
 * @param message human-readable explanation, safe to log and to show
 */
public record ActionError(String code, String message) {

    public static ActionError of(String code, String message) {
        return new ActionError(code, message);
    }
}
