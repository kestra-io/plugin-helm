package io.kestra.plugin.helm;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.runners.TaskRunner;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.helm.models.ChartSource;
import io.kestra.plugin.helm.models.Release;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import io.kestra.plugin.scripts.runner.docker.Docker;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
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
public abstract class AbstractHelm extends Task {
    private static final String DEFAULT_IMAGE = "alpine/helm:4.3.0";

    protected static final String RELEASE_FILE = "release.json";
    protected static final String MANIFEST_FILE = "manifest.yaml";
    protected static final String INLINE_VALUES_FILE = ".kestra-values.yaml";

    protected static final ObjectMapper JSON = JacksonMapper.ofJson();

    @Schema(
        title = "Task runner",
        description = "Runner used to launch the Helm CLI container; defaults to Docker with an empty entrypoint."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    @Valid
    protected TaskRunner<?> taskRunner = Docker.builder()
        .type(Docker.class.getName())
        .entryPoint(new ArrayList<>())
        .build();

    @Schema(
        title = "Container image",
        description = "Image providing the `helm` binary. Defaults to `" + DEFAULT_IMAGE + "`. This plugin targets Helm 4; a Helm 3 image will reject flags such as `--rollback-on-failure` and `--force-replace`."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    protected Property<String> containerImage = Property.ofValue(DEFAULT_IMAGE);

    @Schema(
        title = "Additional environment variables",
        description = "Extra environment variables injected into the Helm container, for registry credentials or proxy settings."
    )
    @PluginProperty(group = "execution")
    protected Property<Map<String, String>> env;

    protected ScriptOutput execute(
        RunContext runContext,
        List<String> commands,
        List<String> outputFiles,
        Map<String, String> extraEnv
    ) throws Exception {
        Map<String, String> environment = new HashMap<>(
            runContext.render(this.env).asMap(String.class, String.class)
        );
        if (extraEnv != null) {
            environment.putAll(extraEnv);
        }

        return new CommandsWrapper(runContext)
            .withTaskRunner(this.taskRunner)
            .withContainerImage(runContext.render(this.containerImage).as(String.class).orElse(DEFAULT_IMAGE))
            .withEnv(environment)
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withCommands(Property.ofValue(commands))
            .withOutputFiles(outputFiles)
            .withFailFast(true)
            .withBeforeCommandsWithOptions(true)
            .run();
    }

    protected String readOutputFile(RunContext runContext, ScriptOutput output, String name) throws Exception {
        URI uri = output.getOutputFiles().get(name);

        if (uri == null) {
            throw new IllegalStateException("Helm did not produce the expected output file '" + name + "'.");
        }

        try (InputStream stream = runContext.storage().getFile(uri)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // Helm writes "Pulled:" and "Digest:" to stdout ahead of the JSON when a chart comes from an
    // OCI registry, so the captured file is not valid JSON on its own. Parsing from the first
    // brace keeps that banner out without discarding anything Helm meant as output.
    protected static Release parseRelease(String raw, String fileName) throws Exception {
        int start = raw.indexOf('{');

        if (start < 0) {
            // Capped: Helm echoes rendered values on some failures, which can include a secret.
            String output = raw.strip();
            throw new IllegalStateException(
                "Helm returned no JSON in '" + fileName + "'. Output was: "
                    + (output.length() > 500 ? output.substring(0, 500) + "... (truncated)" : output)
            );
        }

        return JSON.readValue(raw.substring(start), Release.class);
    }

    protected ChartArgs chartArgs(
        RunContext runContext,
        ChartSource chart,
        Property<Map<String, Object>> values,
        Property<List<String>> valuesFrom
    ) throws Exception {
        if (chart == null) {
            throw new IllegalArgumentException("`chart` is required.");
        }

        ChartSource.ResolvedChart resolved = chart.resolve(runContext);

        StringBuilder flags = new StringBuilder();
        if (resolved.repository() != null) {
            flags.append(" --repo ").append(quote(resolved.repository()));
        }
        if (resolved.version() != null) {
            flags.append(" --version ").append(quote(resolved.version()));
        }

        List<String> references = new ArrayList<>();
        for (String path : runContext.render(valuesFrom).asList(String.class)) {
            flags.append(" --values ").append(quote(path));
            references.add(path);
        }

        Map<String, Object> inline = runContext.render(values).asMap(String.class, Object.class);
        if (!inline.isEmpty()) {
            runContext.workingDir().createFile(
                INLINE_VALUES_FILE,
                JacksonMapper.ofYaml().writeValueAsBytes(inline)
            );
            flags.append(" --values ").append(INLINE_VALUES_FILE);
        }

        return new ChartArgs(resolved.ref(), flags.toString(), resolved.reference(), references);
    }

    protected record ChartArgs(String ref, String flags, String reference, List<String> valuesReferences) {
    }

    protected static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    protected static String outputFile(String name) {
        return name;
    }
}
