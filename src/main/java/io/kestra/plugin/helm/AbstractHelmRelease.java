package io.kestra.plugin.helm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.fabric8.kubernetes.client.Config;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.helm.models.AssetFailureBehavior;
import io.kestra.plugin.helm.services.AssetService;
import io.kestra.plugin.helm.services.KubeConfigService;
import io.kestra.plugin.helm.services.ManifestService;
import io.kestra.plugin.kubernetes.shared.models.Connection;

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
public abstract class AbstractHelmRelease extends AbstractHelm {
    private static final String KUBECONFIG_FILE = ".kubeconfig";

    @Schema(
        title = "Kubernetes connection",
        description = "Cluster credentials, using the same model as the Kubernetes plugin. Mutually exclusive with `kubeconfig`. If neither is set, Helm falls back to whatever kubeconfig or service account is available inside the container."
    )
    @PluginProperty(group = "connection")
    private Connection connection;

    @Schema(
        title = "Inherit ambient cluster configuration",
        description = "When true, `connection` is layered on top of the ambient auto-configuration (system properties, environment, kubeconfig, in-cluster service account) instead of starting from blank."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<Boolean> inheritClusterConfig = Property.ofValue(false);

    @Schema(
        title = "Raw kubeconfig content",
        description = "A complete kubeconfig, used verbatim. Mutually exclusive with `connection`. Supply through `{{ secret('...') }}` rather than inline."
    )
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> kubeconfig;

    @Schema(
        title = "Kubeconfig context",
        description = "Context to select from a multi-context kubeconfig. Only meaningful together with `kubeconfig`."
    )
    @PluginProperty(group = "connection")
    private Property<String> kubeContext;

    @Schema(
        title = "Release name",
        description = "Name of the Helm release to operate on."
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> releaseName;

    @Schema(
        title = "Kubernetes namespace",
        description = "Namespace holding the release."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    protected Property<String> namespace = Property.ofValue("default");

    @Schema(
        title = "Cluster name",
        description = "Recorded on emitted Assets as the `cluster` metadata key. Defaults to the host of the Kubernetes API server. Set it explicitly to match the name your team uses, e.g. `prod-eu`."
    )
    @PluginProperty(group = "advanced")
    protected Property<String> cluster;

    @Schema(
        title = "Region",
        description = "Recorded on emitted Assets as the `region` metadata key, e.g. `europe-west1`. Not inferred, because a cluster can span regions and reading node labels needs extra permissions."
    )
    @PluginProperty(group = "advanced")
    protected Property<String> region;

    @Schema(
        title = "Environment",
        description = "Recorded on emitted Assets as the `environment` metadata key, e.g. `production`."
    )
    @PluginProperty(group = "advanced")
    protected Property<String> environment;

    @Schema(
        title = "Resource kinds to register as Assets",
        description = "Narrows which kinds from the rendered manifest become `KubernetesResource` Assets. Defaults to the common workload, networking, config, and storage kinds."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    protected Property<List<String>> resourceKinds = Property.ofValue(ManifestService.DEFAULT_RESOURCE_KINDS);

    @Schema(
        title = "Behaviour when Asset emission fails",
        description = "One of `WARN` (log the problem and let the task succeed) or `FAIL` (fail the task). Defaults to `WARN` so a successful deploy is never turned into a failure by an Asset problem."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    protected Property<AssetFailureBehavior> assetFailureBehavior = Property.ofValue(AssetFailureBehavior.WARN);

    protected String kubeArgs(RunContext runContext) throws Exception {
        String rKubeconfig = runContext.render(this.kubeconfig).as(String.class).orElse(null);

        if (rKubeconfig != null && this.connection != null) {
            throw new IllegalArgumentException("Set either `connection` or `kubeconfig`, not both.");
        }

        String content = rKubeconfig;
        if (content == null && this.connection != null) {
            Config config = this.connection.toConfig(
                runContext,
                runContext.render(this.inheritClusterConfig).as(Boolean.class).orElse(false)
            );
            content = JacksonMapper.ofYaml().writeValueAsString(
                KubeConfigService.toKubeConfig(config, runContext.render(this.namespace).as(String.class).orElse(null))
            );
        }

        StringBuilder args = new StringBuilder();

        if (content != null) {
            // Written once per run: a task that builds several commands calls this more than once,
            // and createFile throws if the file is already there.
            Path kubeConfigPath = runContext.workingDir().resolve(Path.of(KUBECONFIG_FILE));
            if (!Files.exists(kubeConfigPath)) {
                runContext.workingDir().createFile(KUBECONFIG_FILE, content.getBytes(StandardCharsets.UTF_8));
            }
            args.append(" --kubeconfig ").append(KUBECONFIG_FILE);
        }

        runContext.render(this.kubeContext).as(String.class)
            .ifPresent(context -> args.append(" --kube-context ").append(quote(context)));

        return args.toString();
    }

    protected String resolveCluster(RunContext runContext) throws Exception {
        String explicit = runContext.render(this.cluster).as(String.class).orElse(null);
        if (explicit != null) {
            return explicit;
        }

        if (this.connection == null) {
            return null;
        }

        Config config = this.connection.toConfig(
            runContext,
            runContext.render(this.inheritClusterConfig).as(Boolean.class).orElse(false)
        );

        return KubeConfigService.clusterName(config);
    }

    protected void emitAssets(RunContext runContext, AssetService.Descriptor descriptor) throws Exception {
        AssetService.emit(
            runContext,
            descriptor,
            runContext.render(this.assetFailureBehavior).as(AssetFailureBehavior.class).orElse(AssetFailureBehavior.WARN)
        );
    }
}
