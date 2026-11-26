package io.kestra.plugin.helm;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTaskException;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.helm.models.ChartSource;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the script options inherited from AbstractExecScript, with Template since it needs no cluster.
 */
@KestraTest
class AbstractHelmTest {
    private static final String CHART_YAML = """
        apiVersion: v2
        name: hello
        version: 0.1.0
        """;

    private static final String CONFIGMAP_TEMPLATE = """
        apiVersion: v1
        kind: ConfigMap
        metadata:
          name: {{ .Release.Name }}-config
        data:
          message: {{ .Values.message | default "default-message" | quote }}
        """;

    @Inject
    private RunContextFactory runContextFactory;

    private RunContext runContextWithChart(Template task) throws Exception {
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        runContext.workingDir().createFile("hello/Chart.yaml", CHART_YAML.getBytes(StandardCharsets.UTF_8));
        runContext.workingDir().createFile("hello/templates/configmap.yaml", CONFIGMAP_TEMPLATE.getBytes(StandardCharsets.UTF_8));
        return runContext;
    }

    private static Template.TemplateBuilder<?, ?> template() {
        return Template.builder()
            .id(IdUtils.create())
            .type(Template.class.getName())
            .releaseName(Property.ofValue("render"))
            .chart(ChartSource.builder().path(Property.ofValue("hello")).build());
    }

    @Test
    void beforeCommandsRunBeforeHelmAndUserOutputFilesAreReturned() throws Exception {
        var task = template()
            .beforeCommands(Property.ofValue(List.of("echo 'message: from-before-commands' > extra-values.yaml")))
            .valuesFrom(Property.ofValue(List.of("extra-values.yaml")))
            .outputFiles(Property.ofValue(List.of("extra-values.yaml")))
            .build();
        var runContext = runContextWithChart(task);

        var output = task.run(runContext);

        var manifest = new String(runContext.storage().getFile(output.getManifest()).readAllBytes(), StandardCharsets.UTF_8);
        assertThat(manifest, containsString("from-before-commands"));
        assertThat(output.getOutputFiles().keySet(), is(Set.of("extra-values.yaml")));
    }

    @Test
    void outputFilesGlobDoesNotExposeInlineValues() throws Exception {
        var task = template()
            .values(Property.ofValue(Map.of("message", "secret-value")))
            .beforeCommands(Property.ofValue(List.of("echo report > report.txt")))
            .outputFiles(Property.ofValue(List.of("*")))
            .build();
        var runContext = runContextWithChart(task);

        var output = task.run(runContext);

        assertThat(output.getOutputFiles().keySet(), is(Set.of("report.txt")));
    }

    @Test
    void failingBeforeCommandStopsTheRun() throws Exception {
        var task = template()
            .beforeCommands(Property.ofValue(List.of("false")))
            .build();
        var runContext = runContextWithChart(task);

        var exception = assertThrows(RunnableTaskException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("exit code 1"));
    }

    @Test
    void toStringDoesNotExposeEnvSecrets() throws Exception {
        var task = template()
            .env(Property.ofValue(Map.of("REGISTRY_PASSWORD", "super-secret-value")))
            .build();
        // render caches the value on the property, as run() does
        TestsUtils.mockRunContext(runContextFactory, task, Map.of()).render(task.getEnv()).asMap(String.class, String.class);

        assertThat(task.toString(), not(containsString("super-secret-value")));
    }
}
