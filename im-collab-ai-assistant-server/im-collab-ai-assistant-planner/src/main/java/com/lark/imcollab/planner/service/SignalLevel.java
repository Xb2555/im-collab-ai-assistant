package com.lark.imcollab.planner.service;

public enum SignalLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH;

    static SignalLevel fromScore(int score) {
        if (score <= 0) {
            return NONE;
        }
        if (score <= 34) {
            return LOW;
        }
        if (score <= 69) {
            return MEDIUM;
        }
        return HIGH;
    }
}
