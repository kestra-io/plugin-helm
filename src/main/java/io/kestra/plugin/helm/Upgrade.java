package io.kestra.plugin.helm;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.helm.models.ChartSource;
import io.kestra.plugin.helm.models.DryRunMode;
import io.kestra.plugin.helm.models.Release;
import io.kestra.plugin.helm.models.ReleaseOutput;
import io.kestra.plugin.helm.models.ReleaseResource;
import io.kestra.plugin.helm.models.WaitStrategy;
import io.kestra.plugin.helm.services.AssetService;
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
    title = "Deploy or update a Helm release",
    description = "Runs `helm upgrade --install`, so the release is created when absent and updated when present. Registers the release and every resource it manages as Assets, with the chart and values files as inputs, giving a config-to-cluster lineage graph."
)
@Plugin(
    examples = {
        @Example(
            title = "Deploy a chart from a Helm repository",
            full = true,
            code = """
                id: helm_upgrade
                namespace: company.team

                tasks:
                  - id: deploy
                    type: io.kestra.plugin.helm.Upgrade
                    releaseName: nginx
                    namespace: web
                    createNamespace: true
                    chart:
                      repository: https://charts.bitnami.com/bitnami
                      name: nginx
                      version: "15.4.2"
                    values:
                      image:
                        tag: "1.25.3"
                    connection:
                      inheritClusterConfig: true
                """
        ),
        @Example(
            title = "Deploy with values from Git, waiting for readiness and rolling back on failure",
            full = true,
            code = """
                id: helm_deploy_prod
                namespace: company.team

                inputs:
                  - id: image_tag
                    type: STRING
                    defaults: "1.25.3"

                tasks:
                  - id: deploy
                    type: io.kestra.plugin.core.flow.WorkingDirectory
                    tasks:
                      - id: clone_config
                        type: io.kestra.plugin.git.Clone
                        url: https://github.com/acme/k8s-config
                        branch: main
                        username: "{{ secret('GH_USER') }}"
                        password: "{{ secret('GH_PAT') }}"

                      - id: upgrade
                        type: io.kestra.plugin.helm.Upgrade
                        releaseName: nginx
                        namespace: web
                        cluster: prod-eu
                        region: europe-west1
                        environment: production
                        wait: WATCHER
                        rollbackOnFailure: true
                        timeout: PT10M
                        chart:
                          repository: https://charts.bitnami.com/bitnami
                          name: nginx
                          version: "15.4.2"
                        valuesFrom:
                          - k8s-config/prod/nginx/values.yaml
                        values:
                          image:
                            tag: "{{ inputs.image_tag }}"
                        connection:
                          masterUrl: https://prod-eu.k8s.example.com
                          caCertData: "{{ secret('PROD_EU_CA') }}"
                          oauthToken: "{{ secret('PROD_EU_TOKEN') }}"
                """
        ),
        @Example(
            title = "Validate a change against the API server without applying it",
            full = true,
            code = """
                id: helm_upgrade_dry_run
                namespace: company.team

                tasks:
                  - id: validate
                    type: io.kestra.plugin.helm.Upgrade
                    releaseName: nginx
                    namespace: web
                    dryRun: SERVER
                    chart:
                      repository: https://charts.bitnami.com/bitnami
                      name: nginx
                      version: "15.4.2"
                    kubeconfig: "{{ secret('PROD_EU_KUBECONFIG') }}"
                """
        )
    }
)
public class Upgrade extends AbstractHelmRelease implements RunnableTask<Upgrade.Output> {
    @Schema(title = "Chart to deploy")
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
        title = "Install when the release is absent",
        description = "Adds `--install`, so the task creates the release if it does not exist. Defaults to true, matching the idempotent `helm upgrade --install` behaviour."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Boolean> install = Property.ofValue(true);

    @Schema(
        title = "Create the namespace when absent",
        description = "Adds `--create-namespace`. Helm only honours this when the release is being installed, not on an update."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Boolean> createNamespace = Property.ofValue(false);

    @Schema(
        title = "Readiness strategy",
        description = "Adds `--wait`. One of `WATCHER` (wait until all resources are ready), `HOOK_ONLY` (wait for hooks only) or `LEGACY` (Helm's legacy readiness polling). Leave unset for Helm's own default of `HOOK_ONLY`, which returns before workloads are ready; use `WATCHER` when a downstream task depends on the new pods actually serving."
    )
    @PluginProperty(group = "main")
    private Property<WaitStrategy> wait;

    @Schema(
        title = "Operation timeout",
        description = "Adds `--timeout`. Bounds each Kubernetes operation, and only has an effect while Helm is waiting, i.e. with `wait` or `rollbackOnFailure` set. Helm's own default is 5 minutes."
    )
    @PluginProperty(group = "main")
    private Property<Duration> timeout;

    @Schema(
        title = "Roll back automatically on failure",
        description = "Adds `--rollback-on-failure` (called `--atomic` before Helm 4). Helm reverts the release if the upgrade fails, and defaults `wait` to `WATCHER`. Note the task still fails, so an `errors` block that also rolls back would move the revision a second time."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Boolean> rollbackOnFailure = Property.ofValue(false);

    @Schema(
        title = "Replace resources that cannot be updated in place",
        description = "Adds `--force-replace` (called `--force` before Helm 4). Deletes and recreates resources, which causes downtime."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> forceReplace = Property.ofValue(false);

    @Schema(
        title = "Force server-side apply through conflicts",
        description = "Adds `--force-conflicts`, letting server-side apply take ownership of fields managed by another controller."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> forceConflicts = Property.ofValue(false);

    @Schema(
        title = "Simulate instead of applying",
        description = "Adds `--dry-run`. One of `NONE` (apply for real), `CLIENT` (simulate without contacting the cluster) or `SERVER` (validate the rendered manifests against the API server). `SERVER` is what distinguishes this from the `Template` task. A dry run emits no Assets."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<DryRunMode> dryRun = Property.ofValue(DryRunMode.NONE);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rRelease = runContext.render(this.releaseName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`releaseName` is required."));
        String rNamespace = runContext.render(this.namespace).as(String.class).orElse("default");
        DryRunMode rDryRun = runContext.render(this.dryRun).as(DryRunMode.class).orElse(DryRunMode.NONE);

        ChartArgs chartArgs = chartArgs(runContext, this.chart, this.values, this.valuesFrom);

        String kubeArgs = kubeArgs(runContext);

        StringBuilder flags = new StringBuilder();
        flags.append(" --namespace ").append(quote(rNamespace));
        flags.append(kubeArgs);
        flags.append(chartArgs.flags());

        if (runContext.render(this.install).as(Boolean.class).orElse(true)) {
            flags.append(" --install");
        }
        if (runContext.render(this.createNamespace).as(Boolean.class).orElse(false)) {
            flags.append(" --create-namespace");
        }
        if (runContext.render(this.rollbackOnFailure).as(Boolean.class).orElse(false)) {
            flags.append(" --rollback-on-failure");
        }
        if (runContext.render(this.forceReplace).as(Boolean.class).orElse(false)) {
            flags.append(" --force-replace");
        }
        if (runContext.render(this.forceConflicts).as(Boolean.class).orElse(false)) {
            flags.append(" --force-conflicts");
        }

        runContext.render(this.wait).as(WaitStrategy.class)
            .ifPresent(strategy -> flags.append(" --wait=").append(strategy.flagValue()));

        // Helm parses Go durations, which ISO-8601 Duration.toString() is not.
        runContext.render(this.timeout).as(Duration.class)
            .ifPresent(duration -> flags.append(" --timeout ").append(duration.toSeconds()).append("s"));

        if (rDryRun.enabled()) {
            flags.append(" --dry-run=").append(rDryRun.flagValue());
        }

        List<String> commands = new ArrayList<>();
        List<String> outputFiles = new ArrayList<>();

        commands.add(
            "helm upgrade " + quote(rRelease) + " " + quote(chartArgs.ref()) + flags
                + " --output json > " + outputFile(RELEASE_FILE)
        );
        outputFiles.add(RELEASE_FILE);

        // A dry run writes no release, so `helm get manifest` would fail; the rendered manifest
        // comes back inside the upgrade's own JSON instead.
        if (!rDryRun.enabled()) {
            commands.add(
                "helm get manifest " + quote(rRelease) + " --namespace " + quote(rNamespace)
                    + kubeArgs + " > " + outputFile(MANIFEST_FILE)
            );
            outputFiles.add(MANIFEST_FILE);
        }

        ScriptOutput scriptOutput = execute(runContext, commands, outputFiles, null);

        Release release = parseRelease(readOutputFile(runContext, scriptOutput, RELEASE_FILE), RELEASE_FILE);

        String manifest = rDryRun.enabled()
            ? release.manifest()
            : readOutputFile(runContext, scriptOutput, MANIFEST_FILE);

        URI manifestUri = rDryRun.enabled()
            ? runContext.storage().putFile(
                new ByteArrayInputStream((manifest == null ? "" : manifest).getBytes(StandardCharsets.UTF_8)),
                MANIFEST_FILE
            )
            : scriptOutput.getOutputFiles().get(MANIFEST_FILE);

        List<ReleaseResource> resources = ManifestService.parse(
            manifest,
            rNamespace,
            runContext.render(this.resourceKinds).asList(String.class)
        );

        String releaseName = release.name() != null ? release.name() : rRelease;
        String releaseNamespace = release.namespace() != null ? release.namespace() : rNamespace;

        if (rDryRun.enabled()) {
            runContext.logger().info(
                "Dry run ({}) rendered {} resource(s) for release '{}'; nothing was applied and no Assets were emitted",
                rDryRun.flagValue(),
                resources.size(),
                releaseName
            );
        } else {
            runContext.logger().info(
                "Release '{}' in namespace '{}' is at revision {} with status '{}'",
                releaseName,
                releaseNamespace,
                release.version(),
                release.status()
            );

            emitAssets(runContext, new AssetService.Descriptor(
                resolveCluster(runContext),
                runContext.render(this.region).as(String.class).orElse(null),
                runContext.render(this.environment).as(String.class).orElse(null),
                releaseName,
                releaseNamespace,
                release.version(),
                release.chartName(),
                release.chartVersion(),
                release.appVersion(),
                release.status(),
                chartArgs.reference(),
                chartArgs.valuesReferences(),
                resources,
                false
            ));
        }

        return Output.builder()
            .releaseName(releaseName)
            .namespace(releaseNamespace)
            .revision(release.version())
            .status(release.status())
            .chart(release.chartName())
            .chartVersion(release.chartVersion())
            .appVersion(release.appVersion())
            .firstDeployed(ReleaseOutput.instant(release.firstDeployed()))
            .lastDeployed(ReleaseOutput.instant(release.lastDeployed()))
            .resources(resources)
            .manifest(manifestUri)
            .notes(release.notes())
            .build();
    }

    @SuperBuilder
    @Getter
    public static class Output extends ReleaseOutput {
    }
}
