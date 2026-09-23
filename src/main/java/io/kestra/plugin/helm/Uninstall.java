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
import io.kestra.plugin.helm.models.CascadeStrategy;
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
    title = "Remove a Helm release",
    description = "Runs `helm uninstall` to delete a release and the resources it manages. The release state and rendered manifest are read before the removal, so the corresponding Assets can be soft-deleted rather than left orphaned in the catalog."
)
@Plugin(
    examples = {
        @Example(
            title = "Remove a release",
            full = true,
            code = """
                id: helm_uninstall
                namespace: company.team

                tasks:
                  - id: uninstall
                    type: io.kestra.plugin.helm.Uninstall
                    releaseName: nginx
                    namespace: web
                    connection:
                      inheritClusterConfig: true
                """
        ),
        @Example(
            title = "Tear down a preview environment, waiting for resources to disappear",
            full = true,
            code = """
                id: helm_teardown_preview
                namespace: company.team

                inputs:
                  - id: pr_number
                    type: STRING

                tasks:
                  - id: uninstall
                    type: io.kestra.plugin.helm.Uninstall
                    releaseName: "preview-{{ inputs.pr_number }}"
                    namespace: previews
                    cluster: staging
                    environment: preview
                    cascade: FOREGROUND
                    wait: WATCHER
                    timeout: PT5M
                    ignoreNotFound: true
                    kubeconfig: "{{ secret('STAGING_KUBECONFIG') }}"
                """
        )
    }
)
public class Uninstall extends AbstractHelmRelease implements RunnableTask<Uninstall.Output> {
    private static final String STDERR_FILE = ".helm-stderr";
    private static final String NOT_FOUND_MESSAGE = "release: not found";

    @Schema(
        title = "Retain release history",
        description = "Adds `--keep-history`. Removes the resources but keeps the release history, so the release can still be inspected and rolled back."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Boolean> keepHistory = Property.ofValue(false);

    @Schema(
        title = "Succeed when the release is absent",
        description = "Adds `--ignore-not-found`, and also tolerates the state read that precedes the removal. Use this for teardown flows that may run against an already-removed release."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Boolean> ignoreNotFound = Property.ofValue(false);

    @Schema(
        title = "Readiness strategy",
        description = "Adds `--wait`. One of `WATCHER` (wait until resources are gone), `HOOK_ONLY` (wait for hooks only) or `LEGACY` (Helm's legacy polling). Combine `WATCHER` with `cascade: FOREGROUND` to return only once resources with finalizers are actually gone."
    )
    @PluginProperty(group = "main")
    private Property<WaitStrategy> wait;

    @Schema(
        title = "Deletion cascading strategy",
        description = "Adds `--cascade`. One of `BACKGROUND` (delete dependents in the background, Helm's default), `ORPHAN` (leave dependents in place) or `FOREGROUND` (delete dependents first; combine with `wait` so finalizers complete before the task returns)."
    )
    @PluginProperty(group = "main")
    private Property<CascadeStrategy> cascade;

    @Schema(
        title = "Operation timeout",
        description = "Adds `--timeout`. Bounds each Kubernetes operation, and only has an effect while Helm is waiting. Helm's own default is 5 minutes."
    )
    @PluginProperty(group = "main")
    private Property<Duration> timeout;

    @Schema(
        title = "Skip hooks",
        description = "Adds `--no-hooks`, preventing pre/post-delete hooks from running."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> noHooks = Property.ofValue(false);

    @Schema(
        title = "Simulate instead of removing",
        description = "Adds `--dry-run`. Nothing is removed and no Assets are soft-deleted."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Boolean> dryRun = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rRelease = runContext.render(this.releaseName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`releaseName` is required."));
        String rNamespace = runContext.render(this.namespace).as(String.class).orElse("default");
        boolean rDryRun = runContext.render(this.dryRun).as(Boolean.class).orElse(false);
        boolean rIgnoreNotFound = runContext.render(this.ignoreNotFound).as(Boolean.class).orElse(false);

        String baseFlags = " --namespace " + quote(rNamespace) + kubeArgs(runContext);

        StringBuilder uninstallFlags = new StringBuilder(baseFlags);
        if (runContext.render(this.keepHistory).as(Boolean.class).orElse(false)) {
            uninstallFlags.append(" --keep-history");
        }
        if (rIgnoreNotFound) {
            uninstallFlags.append(" --ignore-not-found");
        }
        if (runContext.render(this.noHooks).as(Boolean.class).orElse(false)) {
            uninstallFlags.append(" --no-hooks");
        }
        if (rDryRun) {
            uninstallFlags.append(" --dry-run");
        }

        runContext.render(this.wait).as(WaitStrategy.class)
            .ifPresent(strategy -> uninstallFlags.append(" --wait=").append(strategy.flagValue()));
        runContext.render(this.cascade).as(CascadeStrategy.class)
            .ifPresent(strategy -> uninstallFlags.append(" --cascade ").append(strategy.flagValue()));
        runContext.render(this.timeout).as(Duration.class)
            .ifPresent(duration -> uninstallFlags.append(" --timeout ").append(duration.toSeconds()).append("s"));

        // `helm uninstall` has no JSON output, and after it runs the release is gone, so the state
        // needed to soft-delete Assets has to be captured first.
        //
        // Tolerating those reads with `|| true` would also swallow a bad kubeconfig, an unreachable
        // API server or an RBAC denial, reporting a broken teardown as a successful one, so only
        // Helm's own "release: not found" is accepted.
        String tolerate = rIgnoreNotFound
            ? " 2> " + STDERR_FILE + " || grep -q " + quote(NOT_FOUND_MESSAGE) + " " + STDERR_FILE
            : "";

        List<String> commands = new ArrayList<>();
        commands.add("helm status " + quote(rRelease) + baseFlags + " --output json > " + outputFile(RELEASE_FILE) + tolerate);
        commands.add("helm get manifest " + quote(rRelease) + baseFlags + " > " + outputFile(MANIFEST_FILE) + tolerate);
        commands.add("helm uninstall " + quote(rRelease) + uninstallFlags);

        ScriptOutput scriptOutput = execute(runContext, commands, List.of(RELEASE_FILE, MANIFEST_FILE), null);

        String releaseJson = readOutputFile(runContext, scriptOutput, RELEASE_FILE);

        if (releaseJson.isBlank()) {
            runContext.logger().info("No release '{}' found in namespace '{}'; nothing to remove", rRelease, rNamespace);

            return Output.builder()
                .releaseName(rRelease)
                .namespace(rNamespace)
                .status("not-found")
                .resources(List.of())
                .build();
        }

        Release release = parseRelease(releaseJson, RELEASE_FILE);
        String manifest = readOutputFile(runContext, scriptOutput, MANIFEST_FILE);

        List<ReleaseResource> resources = ManifestService.parse(
            manifest,
            rNamespace,
            runContext.render(this.resourceKinds).asList(String.class)
        );

        String releaseName = release.name() != null ? release.name() : rRelease;
        String releaseNamespace = release.namespace() != null ? release.namespace() : rNamespace;

        if (rDryRun) {
            runContext.logger().info(
                "Dry run: release '{}' and its {} resource(s) would be removed; no Assets were soft-deleted",
                releaseName,
                resources.size()
            );
        } else {
            runContext.logger().info(
                "Removed release '{}' from namespace '{}' along with {} resource(s)",
                releaseName,
                releaseNamespace,
                resources.size()
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
                "uninstalled",
                null,
                null,
                resources,
                true
            ));
        }

        return Output.builder()
            .releaseName(releaseName)
            .namespace(releaseNamespace)
            .revision(release.version())
            .status(rDryRun ? release.status() : "uninstalled")
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
