package io.kestra.plugin.helm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.helm.models.DryRunMode;
import io.kestra.plugin.helm.models.Release;
import io.kestra.plugin.helm.models.ReleaseOutput;
import io.kestra.plugin.helm.models.ReleaseResource;
import io.kestra.plugin.helm.models.WaitStrategy;
import io.kestra.plugin.helm.services.AssetService;
import io.kestra.plugin.helm.services.ManifestService;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;

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
@Schema(
    title = "Revert a Helm release to an earlier revision",
    description = "Runs `helm rollback` to restore a previous revision of a release, then re-reads the release so the Assets are re-emitted at the revision that is now live."
)
@Plugin(
    examples = {
        @Example(
            title = "Roll back to the previous revision",
            full = true,
            code = """
                id: helm_rollback
                namespace: company.team

                tasks:
                  - id: rollback
                    type: io.kestra.plugin.helm.Rollback
                    releaseName: nginx
                    namespace: web
                    connection:
                      inheritClusterConfig: true
                """
        ),
        @Example(
            title = "Deploy, and revert to a known-good revision if the deploy fails",
            full = true,
            code = """
                id: helm_deploy_with_rollback
                namespace: company.team

                tasks:
                  - id: deploy
                    type: io.kestra.plugin.helm.Upgrade
                    releaseName: nginx
                    namespace: web
                    cluster: prod-eu
                    wait: WATCHER
                    timeout: PT10M
                    chart:
                      repository: https://charts.bitnami.com/bitnami
                      name: nginx
                      version: "15.4.2"
                    connection:
                      masterUrl: https://prod-eu.k8s.example.com
                      caCertData: "{{ secret('PROD_EU_CA') }}"
                      oauthToken: "{{ secret('PROD_EU_TOKEN') }}"

                errors:
                  - id: revert
                    type: io.kestra.plugin.helm.Rollback
                    releaseName: nginx
                    namespace: web
                    cluster: prod-eu
                    wait: WATCHER
                    connection:
                      masterUrl: https://prod-eu.k8s.example.com
                      caCertData: "{{ secret('PROD_EU_CA') }}"
                      oauthToken: "{{ secret('PROD_EU_TOKEN') }}"
                """
        )
    }
)
public class Rollback extends AbstractHelmRelease implements RunnableTask<Rollback.Output> {
    @Schema(
        title = "Revision to roll back to",
        description = "Helm revision number. When unset, Helm rolls back to the revision immediately before the current one."
    )
    @PluginProperty(group = "main")
    private Property<Integer> revision;

    @Schema(
        title = "Readiness strategy",
        description = "Adds `--wait`. One of `WATCHER` (wait until all resources are ready), `HOOK_ONLY` (wait for hooks only) or `LEGACY` (Helm's legacy readiness polling). Leave unset for Helm's own default of `HOOK_ONLY`; use `WATCHER` when a downstream task depends on the restored pods actually serving."
    )
    @PluginProperty(group = "main")
    private Property<WaitStrategy> wait;

    @Schema(
        title = "Operation timeout",
        description = "Adds `--timeout`. Bounds each Kubernetes operation, and only has an effect while Helm is waiting. Helm's own default is 5 minutes."
    )
    @PluginProperty(group = "main")
    private Property<Duration> timeout;

    @Schema(
        title = "Clean up on failure",
        description = "Adds `--cleanup-on-fail`, deleting resources created during a rollback that then fails."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Boolean> cleanupOnFail = Property.ofValue(false);

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
        title = "Skip hooks",
        description = "Adds `--no-hooks`, preventing hooks from running during the rollback."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> noHooks = Property.ofValue(false);

    @Schema(
        title = "Simulate instead of rolling back",
        description = "Adds `--dry-run`. One of `NONE` (roll back for real), `CLIENT` (simulate without contacting the cluster) or `SERVER` (validate against the API server). When simulating, nothing changes on the cluster, no Assets are emitted, and the release state is not re-read."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<DryRunMode> dryRun = Property.ofValue(DryRunMode.NONE);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rRelease = runContext.render(this.releaseName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`releaseName` is required."));
        String rNamespace = runContext.render(this.namespace).as(String.class).orElse("default");
        Integer rRevision = runContext.render(this.revision).as(Integer.class).orElse(null);
        DryRunMode rDryRun = runContext.render(this.dryRun).as(DryRunMode.class).orElse(DryRunMode.NONE);

        String baseFlags = " --namespace " + quote(rNamespace) + kubeArgs(runContext);

        StringBuilder rollbackFlags = new StringBuilder(baseFlags);
        if (runContext.render(this.cleanupOnFail).as(Boolean.class).orElse(false)) {
            rollbackFlags.append(" --cleanup-on-fail");
        }
        if (runContext.render(this.forceReplace).as(Boolean.class).orElse(false)) {
            rollbackFlags.append(" --force-replace");
        }
        if (runContext.render(this.forceConflicts).as(Boolean.class).orElse(false)) {
            rollbackFlags.append(" --force-conflicts");
        }
        if (runContext.render(this.noHooks).as(Boolean.class).orElse(false)) {
            rollbackFlags.append(" --no-hooks");
        }
        if (rDryRun.enabled()) {
            rollbackFlags.append(" --dry-run=").append(rDryRun.flagValue());
        }

        runContext.render(this.wait).as(WaitStrategy.class)
            .ifPresent(strategy -> rollbackFlags.append(" --wait=").append(strategy.flagValue()));
        runContext.render(this.timeout).as(Duration.class)
            .ifPresent(duration -> rollbackFlags.append(" --timeout ").append(duration.toSeconds()).append("s"));

        // Helm takes the revision as a positional argument, not a flag; omitting it rolls back one revision.
        String target = rRevision != null ? " " + rRevision : "";

        List<String> commands = new ArrayList<>();
        commands.add("helm rollback " + quote(rRelease) + target + rollbackFlags);

        if (rDryRun.enabled()) {
            execute(runContext, commands, List.of(), null);

            runContext.logger().info(
                "Dry run ({}) simulated a rollback of release '{}'{}; nothing was applied and no Assets were emitted",
                rDryRun.flagValue(),
                rRelease,
                rRevision != null ? " to revision " + rRevision : ""
            );

            return Output.builder()
                .releaseName(rRelease)
                .namespace(rNamespace)
                .revision(rRevision)
                .resources(List.of())
                .build();
        }

        // `helm rollback` has no JSON output, so the resulting state is read back afterwards.
        commands.add("helm status " + quote(rRelease) + baseFlags + " --output json > " + outputFile(RELEASE_FILE));
        commands.add("helm get manifest " + quote(rRelease) + baseFlags + " > " + outputFile(MANIFEST_FILE));

        ScriptOutput scriptOutput = execute(runContext, commands, List.of(RELEASE_FILE, MANIFEST_FILE), null);

        Release release = parseRelease(readOutputFile(runContext, scriptOutput, RELEASE_FILE), RELEASE_FILE);
        String manifest = readOutputFile(runContext, scriptOutput, MANIFEST_FILE);

        List<ReleaseResource> resources = ManifestService.parse(
            manifest,
            rNamespace,
            runContext.render(this.resourceKinds).asList(String.class)
        );

        String releaseName = release.name() != null ? release.name() : rRelease;
        String releaseNamespace = release.namespace() != null ? release.namespace() : rNamespace;

        runContext.logger().info(
            "Rolled back release '{}' in namespace '{}'; now at revision {} with status '{}'",
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
            null,
            null,
            resources,
            false
        ));

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
            .manifest(scriptOutput.getOutputFiles().get(MANIFEST_FILE))
            .notes(release.notes())
            .build();
    }

    @SuperBuilder
    @Getter
    public static class Output extends ReleaseOutput {
    }
}
