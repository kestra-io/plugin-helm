package io.kestra.plugin.helm.models;

public enum DryRunMode {
    NONE(null),
    CLIENT("client"),
    SERVER("server");

    private final String flagValue;

    DryRunMode(String flagValue) {
        this.flagValue = flagValue;
    }

    public String flagValue() {
        return flagValue;
    }

    public boolean enabled() {
        return flagValue != null;
    }
}
