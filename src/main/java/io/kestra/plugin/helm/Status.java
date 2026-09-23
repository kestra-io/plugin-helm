package io.kestra.plugin.helm;

import java.util.List;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.helm.models.Release;
import io.kestra.plugin.helm.models.ReleaseOutput;
import io.kestra.plugin.helm.models.ReleaseResource;
import io.kestra.plugin.helm.services.AssetService;
import io.kestra.plugin.helm.services.ManifestService;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;

import io.swagger.v3.oas.annotations.media.Schema;
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
    title = "Read the state of a Helm release",
    description = "Runs `helm status` and `helm get manifest` to report the current revision, status, and managed resources of a release without changing anything on the cluster. Registers the release and its resources as Assets."
)
@Plugin(
    examples = {
        @Example(
            title = "Read the status of a release",
            full = true,
            code = """
                id: helm_status
                namespace: company.team

                tasks:
                  - id: status
                    type: io.kestra.plugin.helm.Status
                    releaseName: nginx
                    namespace: web
                    connection:
                      masterUrl: https://prod-eu.k8s.example.com
                      caCertData: "{{ secret('PROD_EU_CA') }}"
                      oauthToken: "{{ secret('PROD_EU_TOKEN') }}"
                """
        ),
        @Example(
            title = "Inspect a previous revision of a release",
            full = true,
            code = """
                id: helm_status_revision
                namespace: company.team

                tasks:
                  - id: status
                    type: io.kestra.plugin.helm.Status
                    releaseName: nginx
                    namespace: web
                    revision: 3
                    cluster: prod-eu
                    region: europe-west1
                    environment: production
                    kubeconfig: "{{ secret('PROD_EU_KUBECONFIG') }}"
                """
        )
    }
)
public class Status extends AbstractHelmRelease implements RunnableTask<Status.Output> {
    @Schema(
        title = "Revision to inspect",
        description = "Reads a specific revision instead of the current one."
    )
    @PluginProperty(group = "main")
    private Property<Integer> revision;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rRelease = runContext.render(this.releaseName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`releaseName` is required."));
        String rNamespace = runContext.render(this.namespace).as(String.class).orElse("default");
        Integer rRevision = runContext.render(this.revision).as(Integer.class).orElse(null);

        StringBuilder flags = new StringBuilder();
        flags.append(" --namespace ").append(quote(rNamespace));
        flags.append(kubeArgs(runContext));
        if (rRevision != null) {
            flags.append(" --revision ").append(rRevision);
        }

        List<String> commands = List.of(
            "helm status " + quote(rRelease) + flags + " --output json > " + outputFile(RELEASE_FILE),
            "helm get manifest " + quote(rRelease) + flags + " > " + outputFile(MANIFEST_FILE)
        );

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
