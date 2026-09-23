package io.kestra.plugin.helm;

import java.net.URI;
import java.util.List;
import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.helm.models.ChartSource;
import io.kestra.plugin.helm.models.ReleaseResource;
import io.kestra.plugin.helm.services.ManifestService;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Render a Helm chart without touching a cluster",
    description = "Runs `helm template` to render a chart to Kubernetes manifests, for review or diffing before an `Upgrade` applies it. Never contacts the cluster and emits no Assets."
)
@Plugin(
    examples = {
        @Example(
            title = "Render a chart from a Helm repository",
            full = true,
            code = """
                id: helm_template
                namespace: company.team

                tasks:
                  - id: render
                    type: io.kestra.plugin.helm.Template
                    releaseName: nginx
                    namespace: web
                    chart:
                      repository: https://charts.bitnami.com/bitnami
                      name: nginx
                      version: "15.4.2"
                    values:
                      image:
                        tag: "1.25.3"
                """
        ),
        @Example(
            title = "Render a chart and values held in Git",
            full = true,
            code = """
                id: helm_template_git
                namespace: company.team

                tasks:
                  - id: render
                    type: io.kestra.plugin.core.flow.WorkingDirectory
                    tasks:
                      - id: clone
                        type: io.kestra.plugin.git.Clone
                        url: https://github.com/acme/k8s-config
                        branch: main
                        username: "{{ secret('GH_USER') }}"
                        password: "{{ secret('GH_PAT') }}"

                      - id: template
                        type: io.kestra.plugin.helm.Template
                        releaseName: nginx
                        namespace: web
                        chart:
                          path: k8s-config/charts/nginx
                        valuesFrom:
                          - k8s-config/prod/nginx/values.yaml
                """
        )
    }
)
public class Template extends AbstractHelm implements RunnableTask<Template.Output> {
    @Schema(
        title = "Release name",
        description = "Name Helm renders the chart under; affects generated resource names and `.Release.Name`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> releaseName;

    @Schema(
        title = "Kubernetes namespace",
        description = "Namespace the chart is rendered for; affects `.Release.Namespace` and namespaced resource metadata."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<String> namespace = Property.ofValue("default");

    @Schema(title = "Chart to render")
    @NotNull
    @PluginProperty(group = "source")
    private ChartSource chart;

    @Schema(
        title = "Inline chart values",
        description = "Applied after every `valuesFrom` file, so these win on conflict."
    )
    @PluginProperty(group = "source")
    private Property<Map<String, Object>> values;

    @Schema(
        title = "Values files",
        description = "Paths to values files relative to the working directory, applied in order. Later files override earlier ones, and inline `values` override them all."
    )
    @PluginProperty(group = "source")
    private Property<List<String>> valuesFrom;

    @Schema(
        title = "Resource kinds to report",
        description = "Narrows which kinds from the rendered manifest appear in the `resources` output."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<List<String>> resourceKinds = Property.ofValue(ManifestService.DEFAULT_RESOURCE_KINDS);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rRelease = runContext.render(this.releaseName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`releaseName` is required."));
        String rNamespace = runContext.render(this.namespace).as(String.class).orElse("default");

        ChartArgs chartArgs = chartArgs(runContext, this.chart, this.values, this.valuesFrom);

        List<String> commands = List.of(
            "helm template " + quote(rRelease) + " " + quote(chartArgs.ref())
                + " --namespace " + quote(rNamespace)
                + chartArgs.flags()
                + " > " + outputFile(MANIFEST_FILE)
        );

        ScriptOutput scriptOutput = execute(runContext, commands, List.of(MANIFEST_FILE), null);

        String manifest = readOutputFile(runContext, scriptOutput, MANIFEST_FILE);
        List<ReleaseResource> resources = ManifestService.parse(
            manifest,
            rNamespace,
            runContext.render(this.resourceKinds).asList(String.class)
        );

        runContext.logger().info("Rendered {} resource(s) from chart '{}'", resources.size(), chartArgs.reference());

        return Output.builder()
            .manifest(scriptOutput.getOutputFiles().get(MANIFEST_FILE))
            .resources(resources)
            .chartReference(chartArgs.reference())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Rendered manifest",
            description = "URI of the rendered manifest in Kestra's internal storage, diffable across executions."
        )
        private final URI manifest;

        @Schema(
            title = "Rendered resources",
            description = "Kubernetes resources the chart renders, for use by downstream tasks."
        )
        private final List<ReleaseResource> resources;

        @Schema(
            title = "Chart reference",
            description = "Resolved reference of the rendered chart, e.g. `https://charts.bitnami.com/bitnami/nginx:15.4.2`."
        )
        private final String chartReference;
    }
}
