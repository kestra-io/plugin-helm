package io.kestra.plugin.helm.models;

import java.util.Arrays;
import java.util.Locale;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Status of a Helm release revision, as Helm records it on the release.")
public enum ReleaseStatus {
    UNKNOWN,
    DEPLOYED,
    UNINSTALLED,
    SUPERSEDED,
    FAILED,
    UNINSTALLING,
    PENDING_INSTALL,
    PENDING_UPGRADE,
    PENDING_ROLLBACK;

    public String helmValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public boolean isPending() {
        return this == PENDING_INSTALL || this == PENDING_UPGRADE || this == PENDING_ROLLBACK;
    }

    public static ReleaseStatus fromHelm(String value) {
        if (value == null) {
            return UNKNOWN;
        }

        return Arrays.stream(values())
            .filter(status -> status.helmValue().equals(value))
            .findFirst()
            .orElse(UNKNOWN);
    }
}
