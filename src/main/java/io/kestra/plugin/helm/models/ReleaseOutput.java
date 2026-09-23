package io.kestra.plugin.helm.models;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import io.kestra.core.models.tasks.Output;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
public class ReleaseOutput implements Output {
    @Schema(title = "Release name")
    private final String releaseName;

    @Schema(title = "Namespace holding the release")
    private final String namespace;

    @Schema(
        title = "Release revision",
        description = "Helm revision number of the release after the operation."
    )
    private final Integer revision;

    @Schema(
        title = "Release status",
        description = "Helm status, such as `deployed`, `failed`, `pending-upgrade`, or `superseded`."
    )
    private final String status;

    @Schema(title = "Chart name")
    private final String chart;

    @Schema(title = "Chart version")
    private final String chartVersion;

    @Schema(
        title = "Application version",
        description = "The chart's `appVersion`, i.e. the version of the application it packages."
    )
    private final String appVersion;

    @Schema(
        title = "First deployment time",
        description = "When revision 1 of this release was installed. Null when Helm reports no deployment time."
    )
    private final Instant firstDeployed;

    @Schema(
        title = "Last deployment time",
        description = "When the current revision was deployed. Null when Helm reports no deployment time."
    )
    private final Instant lastDeployed;

    @Schema(
        title = "Managed resources",
        description = "Kubernetes resources rendered by the chart, for use by downstream tasks."
    )
    private final List<ReleaseResource> resources;

    @Schema(
        title = "Rendered manifest",
        description = "URI of the rendered manifest in Kestra's internal storage, diffable across executions."
    )
    private final URI manifest;

    @Schema(
        title = "Chart notes",
        description = "The chart's rendered `NOTES.txt`."
    )
    private final String notes;

    public static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);

            // Helm reports an unset timestamp as the Go zero time, which would otherwise surface as a real year-1 date.
            return parsed.getYear() <= 1 ? null : parsed.toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
