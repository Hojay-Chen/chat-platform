package com.luxera.companion.contracts.application;

/**
 * LAP v1 — how much damage an action could do if the caller is wrong or malicious.
 *
 * <p>Risk is the axis the permission engine actually gates on: {@code NONE}/{@code LOW} run
 * unattended, {@code MEDIUM} requires an explicit confirmation, {@code HIGH}/{@code CRITICAL}
 * are refused outright for now.
 */
public enum RiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
