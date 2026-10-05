package io.kestra.plugin.helm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.helm.models.ChartSource;
import io.kestra.plugin.helm.models.DryRunMode;
import io.kestra.plugin.helm.models.ReleaseResource;
import io.kestra.plugin.helm.models.ReleaseStatus;
import io.kestra.plugin.helm.models.WaitStrategy;
import io.kestra.plugin.scripts.runner.docker.Docker;

import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the tasks against the kind cluster created by .github/setup-unit.sh.
 */
@KestraTest
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class HelmLifecycleTest {
    private static final Path KUBECONFIG = Path.of("/tmp/kestra-helm-kubeconfig.yaml");
    private static final Path HOST_KUBECONFIG = Path.of("/tmp/kestra-helm-host-kubeconfig.yaml");
    private static final String NAMESPACE = "helm-it";

    private static final String CHART_YAML = """
        apiVersion: v2
        name: hello
        version: 0.1.0
        appVersion: "1.0.0"
        description: Minimal chart used by the plugin-helm integration tests
        """;

    private static final String CONFIGMAP_TEMPLATE = """
        apiVersion: v1
        kind: ConfigMap
        metadata:
          name: {{ .Release.Name }}-config
        data:
          message: {{ .Values.message | default "default-message" | quote }}
        """;

    private static final String BROKEN_TEMPLATE = """
        {{- if .Values.broken }}
        apiVersion: v1
        kind: ConfigMap
        metadata:
          name: Not_A_Valid_Name
        {{- end }}
        """;

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeEach
    void requireCluster() {
        assumeTrue(
            Files.exists(KUBECONFIG),
            "No cluster kubeconfig at " + KUBECONFIG + "; run .github/setup-unit.sh first"
        );
    }

    // TestsUtils.mockRunContext rather than runContextFactory.of(): only the former runs the
    // run-context initializer, and the Docker task runner renders on a clone that is left
    // half-built without it, failing with a NullPointerException on a null Optional.
    private RunContext runContextFor(Task task) {
        return TestsUtils.mockRunContext(runContextFactory, task, Map.of());
    }

    private RunContext runContextWithChart(Task task) throws Exception {
        RunContext runContext = runContextFor(task);
        runContext.workingDir().createFile("hello/Chart.yaml", CHART_YAML.getBytes(StandardCharsets.UTF_8));
        runContext.workingDir().createFile("hello/templates/configmap.yaml", CONFIGMAP_TEMPLATE.getBytes(StandardCharsets.UTF_8));
        runContext.workingDir().createFile("hello/templates/broken.yaml", BROKEN_TEMPLATE.getBytes(StandardCharsets.UTF_8));

        return runContext;
    }

    private static String kubeconfig() throws Exception {
        return Files.readString(KUBECONFIG);
    }

    // The Helm container joins kind's network so it can resolve the control plane by name, and
    // the entrypoint is emptied because the image would otherwise run `helm /bin/sh -c ...`.
    private static Docker taskRunner() {
        return Docker.builder()
            .type(Docker.class.getName())
            .entryPoint(new ArrayList<>())
            .networkMode("kind")
            .build();
    }

    @Test
    void shouldInstallReadAndRemoveARelease() throws Exception {
        String release = "it-" + IdUtils.create().toLowerCase();

        Upgrade upgrade = Upgrade.builder()
            .id(IdUtils.create())
            .type(Upgrade.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .createNamespace(Property.ofValue(true))
            .wait(Property.ofValue(WaitStrategy.WATCHER))
            .cluster(Property.ofValue("kind-it"))
            .chart(ChartSource.builder().path(Property.ofValue("hello")).build())
            .values(Property.ofValue(Map.of("message", "from-integration-test")))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();

        Upgrade.Output installed = upgrade.run(runContextWithChart(upgrade));

        assertThat(installed.getReleaseName(), is(release));
        assertThat(installed.getNamespace(), is(NAMESPACE));
        assertThat(installed.getRevision(), is(1));
        assertThat(installed.getStatus(), is("deployed"));
        assertThat(installed.getChart(), is("hello"));
        assertThat(installed.getChartVersion(), is("0.1.0"));
        assertThat(installed.getAppVersion(), is("1.0.0"));
        assertThat(installed.getManifest(), is(notNullValue()));
        assertThat(installed.getLastDeployed(), is(notNullValue()));
        assertThat(
            installed.getResources().stream().map(ReleaseResource::kind).toList(),
            contains("ConfigMap")
        );
        assertThat(installed.getResources().getFirst().name(), is(release + "-config"));

        Status statusTask = Status.builder()
            .id(IdUtils.create())
            .type(Status.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();

        Status.Output status = statusTask.run(runContextFor(statusTask));

        assertThat(status.getRevision(), is(1));
        assertThat(status.getStatus(), is("deployed"));
        assertThat(status.getResources(), hasSize(1));

        Upgrade second = Upgrade.builder()
            .id(IdUtils.create())
            .type(Upgrade.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .wait(Property.ofValue(WaitStrategy.WATCHER))
            .chart(ChartSource.builder().path(Property.ofValue("hello")).build())
            .values(Property.ofValue(Map.of("message", "second-revision")))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();

        assertThat(second.run(runContextWithChart(second)).getRevision(), is(2));

        Rollback rollbackTask = Rollback.builder()
            .id(IdUtils.create())
            .type(Rollback.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .revision(Property.ofValue(1))
            .wait(Property.ofValue(WaitStrategy.WATCHER))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();

        Rollback.Output rolledBack = rollbackTask.run(runContextFor(rollbackTask));

        // Helm records a rollback as a new revision rather than moving back to the old number.
        assertThat(rolledBack.getRevision(), is(3));
        assertThat(rolledBack.getStatus(), is("deployed"));
        assertThat(rolledBack.getResources(), hasSize(1));

        Uninstall uninstall = Uninstall.builder()
            .id(IdUtils.create())
            .type(Uninstall.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .wait(Property.ofValue(WaitStrategy.WATCHER))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();

        Uninstall.Output removed = uninstall.run(runContextFor(uninstall));

        assertThat(removed.getStatus(), is("uninstalled"));
        // Captured before the delete, so an uninstall still reports what it removed.
        assertThat(removed.getResources(), hasSize(1));

        Uninstall missingTask = Uninstall.builder()
            .id(IdUtils.create())
            .type(Uninstall.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .ignoreNotFound(Property.ofValue(true))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();

        Uninstall.Output missing = missingTask.run(runContextFor(missingTask));

        assertThat(missing.getStatus(), is("not-found"));
        assertThat(missing.getResources(), is(List.of()));
    }

    @Test
    void shouldRenderAChartWithoutTouchingTheCluster() throws Exception {
        Template template = Template.builder()
            .id(IdUtils.create())
            .type(Template.class.getName())
            .releaseName(Property.ofValue("render"))
            .namespace(Property.ofValue(NAMESPACE))
            .chart(ChartSource.builder().path(Property.ofValue("hello")).build())
            .values(Property.ofValue(Map.of("message", "rendered")))
            .taskRunner(taskRunner())
            .build();

        Template.Output rendered = template.run(runContextWithChart(template));

        assertThat(rendered.getManifest(), is(notNullValue()));
        assertThat(rendered.getResources(), hasSize(1));
        assertThat(rendered.getResources().getFirst().kind(), is("ConfigMap"));
        assertThat(rendered.getChartReference(), is("hello"));
    }

    @Test
    void shouldSimulateARollbackWithoutChangingTheRelease() throws Exception {
        String release = "dr-" + IdUtils.create().toLowerCase();

        Upgrade install = Upgrade.builder()
            .id(IdUtils.create())
            .type(Upgrade.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .createNamespace(Property.ofValue(true))
            .wait(Property.ofValue(WaitStrategy.WATCHER))
            .chart(ChartSource.builder().path(Property.ofValue("hello")).build())
            .values(Property.ofValue(Map.of("message", "first")))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();
        install.run(runContextWithChart(install));

        Upgrade second = Upgrade.builder()
            .id(IdUtils.create())
            .type(Upgrade.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .wait(Property.ofValue(WaitStrategy.WATCHER))
            .chart(ChartSource.builder().path(Property.ofValue("hello")).build())
            .values(Property.ofValue(Map.of("message", "second")))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();
        assertThat(second.run(runContextWithChart(second)).getRevision(), is(2));

        Rollback simulated = Rollback.builder()
            .id(IdUtils.create())
            .type(Rollback.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .revision(Property.ofValue(1))
            .dryRun(Property.ofValue(DryRunMode.CLIENT))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();

        Rollback.Output output = simulated.run(runContextFor(simulated));

        // The dry-run branch skips the state re-read, so it echoes the requested revision
        // rather than reporting one read back from the cluster.
        assertThat(output.getReleaseName(), is(release));
        assertThat(output.getRevision(), is(1));
        assertThat(output.getResources(), is(List.of()));

        Status after = Status.builder()
            .id(IdUtils.create())
            .type(Status.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();

        // Still on revision 2: the simulation must not have moved the release.
        assertThat(after.run(runContextFor(after)).getRevision(), is(2));

        Uninstall cleanup = Uninstall.builder()
            .id(IdUtils.create())
            .type(Uninstall.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();
        cleanup.run(runContextFor(cleanup));
    }

    @Test
    void shouldTriggerOnRealReleaseTransitions() throws Exception {
        assumeTrue(
            Files.exists(HOST_KUBECONFIG),
            "No host kubeconfig at " + HOST_KUBECONFIG + "; run .github/setup-unit.sh first"
        );

        String release = "tr-" + IdUtils.create().toLowerCase();

        Upgrade install = Upgrade.builder()
            .id(IdUtils.create())
            .type(Upgrade.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .createNamespace(Property.ofValue(true))
            .wait(Property.ofValue(WaitStrategy.WATCHER))
            .chart(ChartSource.builder().path(Property.ofValue("hello")).build())
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();
        install.run(runContextWithChart(install));

        ReleaseTrigger trigger = releaseTrigger(release).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        Map<String, Object> installed = onlyRelease(trigger.evaluate(context.getKey(), context.getValue()));
        assertThat(installed.get("revision"), is(1));
        assertThat(installed.get("status"), is("deployed"));
        assertThat(installed.get("chart"), is("hello"));
        assertThat(installed.get("chartVersion"), is("0.1.0"));
        assertThat(installed.get("appVersion"), is("1.0.0"));
        assertThat(installed.get("cluster"), is("kind-it"));

        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isEmpty(), is(true));

        Upgrade broken = Upgrade.builder()
            .id(IdUtils.create())
            .type(Upgrade.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .chart(ChartSource.builder().path(Property.ofValue("hello")).build())
            .values(Property.ofValue(Map.of("broken", true)))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();
        assertThrows(Exception.class, () -> broken.run(runContextWithChart(broken)));

        Map<String, Object> failed = onlyRelease(trigger.evaluate(context.getKey(), context.getValue()));
        assertThat(failed.get("revision"), is(2));
        assertThat(failed.get("status"), is("failed"));
        assertThat(failed.get("previousStatus"), is("deployed"));
        assertThat(failed.get("description"), is(notNullValue()));

        Rollback rollback = Rollback.builder()
            .id(IdUtils.create())
            .type(Rollback.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .revision(Property.ofValue(1))
            .wait(Property.ofValue(WaitStrategy.WATCHER))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();
        rollback.run(runContextFor(rollback));

        Map<String, Object> recovered = onlyRelease(trigger.evaluate(context.getKey(), context.getValue()));
        assertThat(recovered.get("revision"), is(3));
        assertThat(recovered.get("status"), is("deployed"));
        assertThat(recovered.get("previousStatus"), is("failed"));

        // Revision 2 is still `failed` in the release history, but it is no longer the latest.
        ReleaseTrigger failedOnly = releaseTrigger(release)
            .statuses(Property.ofValue(List.of(ReleaseStatus.FAILED)))
            .build();
        var failedOnlyContext = TestsUtils.mockTrigger(runContextFactory, failedOnly);
        assertThat(failedOnly.evaluate(failedOnlyContext.getKey(), failedOnlyContext.getValue()).isEmpty(), is(true));

        Uninstall cleanup = Uninstall.builder()
            .id(IdUtils.create())
            .type(Uninstall.class.getName())
            .releaseName(Property.ofValue(release))
            .namespace(Property.ofValue(NAMESPACE))
            .kubeconfig(Property.ofValue(kubeconfig()))
            .taskRunner(taskRunner())
            .build();
        cleanup.run(runContextFor(cleanup));
    }

    private static ReleaseTrigger.ReleaseTriggerBuilder<?, ?> releaseTrigger(String release) throws Exception {
        return ReleaseTrigger.builder()
            .id("release-" + IdUtils.create())
            .type(ReleaseTrigger.class.getName())
            .namespace(Property.ofValue(NAMESPACE))
            .releaseName(Property.ofValue(release))
            .cluster(Property.ofValue("kind-it"))
            .kubeconfig(Property.ofValue(Files.readString(HOST_KUBECONFIG)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyRelease(Optional<Execution> execution) {
        assertThat(execution.isPresent(), is(true));

        List<Map<String, Object>> releases = (List<Map<String, Object>>) execution.get().getTrigger().getVariables().get("releases");
        assertThat(releases, hasSize(1));

        return releases.getFirst();
    }
}
