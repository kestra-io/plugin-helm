package io.kestra.plugin.helm.models;

import java.util.stream.Stream;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Jacksonized
@ToString
@Schema(
    title = "Where the chart comes from",
    description = "Either `repository` + `name` or `path` must be set, but not both. An OCI registry is a `repository` starting with `oci://`. To deploy a chart held in Git, clone it with `io.kestra.plugin.git.Clone` inside a `WorkingDirectory` and point `path` at the checkout."
)
public class ChartSource {
    @Schema(
        title = "Helm repository URL or OCI registry",
        description = "A Helm repository (`https://charts.bitnami.com/bitnami`) or an OCI registry (`oci://registry.example.com/charts`). Requires `name`."
    )
    @PluginProperty(group = "source")
    private Property<String> repository;

    @Schema(
        title = "Chart name",
        description = "Name of the chart within `repository`, e.g. `nginx`."
    )
    @PluginProperty(group = "source")
    private Property<String> name;

    @Schema(
        title = "Chart version",
        description = "Exact chart version to use, e.g. `15.4.2`. Defaults to the latest version the repository offers."
    )
    @PluginProperty(group = "source")
    private Property<String> version;

    @Schema(
        title = "Path to a local chart",
        description = "Path to an unpacked chart directory or a packaged `.tgz`, relative to the task's working directory. Use this with `WorkingDirectory` to deploy a chart produced by an earlier task."
    )
    @PluginProperty(group = "source")
    private Property<String> path;

    public ResolvedChart resolve(RunContext runContext) throws Exception {
        String rRepository = runContext.render(this.repository).as(String.class).orElse(null);
        String rName = runContext.render(this.name).as(String.class).orElse(null);
        String rVersion = runContext.render(this.version).as(String.class).orElse(null);
        String rPath = runContext.render(this.path).as(String.class).orElse(null);

        long set = Stream.of(rRepository != null || rName != null, rPath != null)
            .filter(Boolean::booleanValue)
            .count();

        if (set == 0) {
            throw new IllegalArgumentException("A chart source is required: set either `repository` + `name`, or `path`.");
        }
        if (set > 1) {
            throw new IllegalArgumentException("Only one chart source may be set, but both `repository`/`name` and `path` were provided.");
        }

        if (rPath != null) {
            return new ResolvedChart(rPath, null, rVersion, rPath);
        }

        if (rName == null) {
            throw new IllegalArgumentException("`name` is required when `repository` is set.");
        }

        if (rRepository == null) {
            return new ResolvedChart(rName, null, rVersion, rName + (rVersion == null ? "" : ":" + rVersion));
        }

        if (rRepository.startsWith("oci://")) {
            String ref = rRepository.replaceAll("/$", "") + "/" + rName;
            return new ResolvedChart(ref, null, rVersion, ref + (rVersion == null ? "" : ":" + rVersion));
        }

        return new ResolvedChart(
            rName,
            rRepository,
            rVersion,
            rRepository.replaceAll("/$", "") + "/" + rName + (rVersion == null ? "" : ":" + rVersion)
        );
    }

    public record ResolvedChart(String ref, String repository, String version, String reference) {
    }
}
