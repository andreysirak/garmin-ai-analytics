package dev.garmin.evaluation.core;

public enum Status {
    PASS, FAIL, ERROR, SKIP, NEEDS_REVIEW;

    public boolean definitive() {
        return this == PASS || this == FAIL;
    }
}
