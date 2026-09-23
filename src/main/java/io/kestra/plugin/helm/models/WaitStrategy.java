package io.kestra.plugin.helm.models;

public enum WaitStrategy {
    WATCHER("watcher"),
    HOOK_ONLY("hookOnly"),
    LEGACY("legacy");

    private final String flagValue;

    WaitStrategy(String flagValue) {
        this.flagValue = flagValue;
    }

    public String flagValue() {
        return flagValue;
    }
}
