package io.kestra.plugin.helm.models;

public enum CascadeStrategy {
    BACKGROUND("background"),
    ORPHAN("orphan"),
    FOREGROUND("foreground");

    private final String flagValue;

    CascadeStrategy(String flagValue) {
        this.flagValue = flagValue;
    }

    public String flagValue() {
        return flagValue;
    }
}
