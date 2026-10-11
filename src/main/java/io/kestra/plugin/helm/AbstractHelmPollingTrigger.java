package io.kestra.plugin.helm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import io.fabric8.kubernetes.client.Config;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.helm.services.KubeConfigService;
import io.kestra.plugin.kubernetes.shared.models.Connection;

import io.swagger.v3.oas.annotations.media.Schema;
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
public abstract class AbstractHelmPollingTrigger extends AbstractTrigger implements PollingTriggerInterface {
    @Schema(
        title = "Kubernetes connection",
        description = "Cluster credentials, using the same model as the Kubernetes plugin. Mutually exclusive with `kubeconfig`. If neither is set, the trigger uses the kubeconfig or service account available to the Kestra worker."
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
        description = "A complete kubeconfig. Mutually exclusive with `connection`. Supply through `{{ secret('...') }}` rather than inline.\n\nUnlike the tasks, which hand the kubeconfig to `helm` inside a container, the trigger loads it on the Kestra worker, so it must carry its credentials inline: `exec`, `auth-provider`, `tokenFile`, `client-certificate`, `client-key`, and `certificate-authority` are rejected. Use the `*-data` fields and a `token`, or `connection` with an `oauthTokenProvider` for short-lived cloud credentials such as EKS or GKE."
    )
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> kubeconfig;

    @Schema(
        title = "Kubeconfig context",
        description = "Context to select from `kubeconfig`, or from the worker's ambient kubeconfig when neither `kubeconfig` nor `connection` is set."
    )
    @PluginProperty(group = "connection")
    private Property<String> kubeContext;

    @Schema(
        title = "Cluster name",
        description = "Reported as `cluster` on each release in the trigger output, resolved the same way as on the tasks so the two can be joined. Defaults to the host of the Kubernetes API server when `connection` is used, and is null otherwise. Set it explicitly to match the name your team uses, e.g. `prod-eu`."
    )
    @PluginProperty(group = "advanced")
    protected Property<String> cluster;

    @Schema(
        title = "Polling interval",
        description = "ISO-8601 duration between two reads of the cluster."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private final Duration interval = Duration.ofMinutes(1);

    protected KubeConfigService.ClusterAccess clusterAccess(RunContext runContext) throws Exception {
        return KubeConfigService.resolve(runContext, this.connection, this.inheritClusterConfig, this.kubeconfig, this.cluster);
    }

    protected Config kubernetesConfig(RunContext runContext, KubeConfigService.ClusterAccess access) throws Exception {
        String rKubeContext = runContext.render(this.kubeContext).as(String.class).orElse(null);

        if (access.kubeconfig() != null) {
            KubeConfigService.requireInlineCredentials(access.kubeconfig());
            Path file = runContext.workingDir().createTempFile(access.kubeconfig().getBytes(StandardCharsets.UTF_8), ".kubeconfig");
            return Config.fromKubeconfig(rKubeContext, file.toFile());
        }

        if (access.connectionConfig() != null) {
            return access.connectionConfig();
        }

        return Config.autoConfigure(rKubeContext);
    }
}
