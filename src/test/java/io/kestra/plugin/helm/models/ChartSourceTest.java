package io.kestra.plugin.helm.models;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ChartSourceTest {
    @Inject
    private RunContextFactory runContextFactory;

    private RunContext runContext() {
        return runContextFactory.of();
    }

    @Test
    void shouldUseALocalPathAsTheChartReference() throws Exception {
        ChartSource.ResolvedChart resolved = ChartSource.builder()
            .path(Property.ofValue("charts/hello"))
            .build()
            .resolve(runContext());

        assertThat(resolved.ref(), is("charts/hello"));
        assertThat(resolved.repository(), is(nullValue()));
        assertThat(resolved.reference(), is("charts/hello"));
    }

    @Test
    void shouldKeepRepositorySeparateFromTheChartName() throws Exception {
        // A Helm repository is passed through --repo, so the name stays the ref.
        ChartSource.ResolvedChart resolved = ChartSource.builder()
            .repository(Property.ofValue("https://helm.github.io/examples"))
            .name(Property.ofValue("hello-world"))
            .version(Property.ofValue("0.1.0"))
            .build()
            .resolve(runContext());

        assertThat(resolved.ref(), is("hello-world"));
        assertThat(resolved.repository(), is("https://helm.github.io/examples"));
        assertThat(resolved.version(), is("0.1.0"));
        assertThat(resolved.reference(), is("https://helm.github.io/examples/hello-world:0.1.0"));
    }

    @Test
    void shouldFoldRepositoryAndNameIntoASingleOciReference() throws Exception {
        // OCI has no --repo flag: the ref is the full registry path.
        ChartSource.ResolvedChart resolved = ChartSource.builder()
            .repository(Property.ofValue("oci://ghcr.io/stefanprodan/charts"))
            .name(Property.ofValue("podinfo"))
            .version(Property.ofValue("6.15.0"))
            .build()
            .resolve(runContext());

        assertThat(resolved.ref(), is("oci://ghcr.io/stefanprodan/charts/podinfo"));
        assertThat(resolved.repository(), is(nullValue()));
        assertThat(resolved.version(), is("6.15.0"));
    }

    @Test
    void shouldNotDoubleUpSlashesOnATrailingSlashRepository() throws Exception {
        ChartSource.ResolvedChart oci = ChartSource.builder()
            .repository(Property.ofValue("oci://ghcr.io/stefanprodan/charts/"))
            .name(Property.ofValue("podinfo"))
            .build()
            .resolve(runContext());

        ChartSource.ResolvedChart repo = ChartSource.builder()
            .repository(Property.ofValue("https://helm.github.io/examples/"))
            .name(Property.ofValue("hello-world"))
            .build()
            .resolve(runContext());

        assertThat(oci.ref(), is("oci://ghcr.io/stefanprodan/charts/podinfo"));
        assertThat(repo.reference(), is("https://helm.github.io/examples/hello-world"));
    }

    @Test
    void shouldAcceptABareChartNameForAlreadyAddedRepositories() throws Exception {
        ChartSource.ResolvedChart resolved = ChartSource.builder()
            .name(Property.ofValue("nginx"))
            .build()
            .resolve(runContext());

        assertThat(resolved.ref(), is("nginx"));
        assertThat(resolved.repository(), is(nullValue()));
    }

    @Test
    void shouldOmitTheVersionFromTheReferenceWhenUnset() throws Exception {
        ChartSource.ResolvedChart resolved = ChartSource.builder()
            .repository(Property.ofValue("https://helm.github.io/examples"))
            .name(Property.ofValue("hello-world"))
            .build()
            .resolve(runContext());

        assertThat(resolved.version(), is(nullValue()));
        assertThat(resolved.reference(), is("https://helm.github.io/examples/hello-world"));
    }

    @Test
    void shouldRejectAChartWithNoSourceAtAll() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> ChartSource.builder().build().resolve(runContext())
        );

        assertThat(thrown.getMessage(), containsString("A chart source is required"));
    }

    @Test
    void shouldRejectAmbiguousSources() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> ChartSource.builder()
                .repository(Property.ofValue("https://helm.github.io/examples"))
                .name(Property.ofValue("hello-world"))
                .path(Property.ofValue("charts/hello"))
                .build()
                .resolve(runContext())
        );

        assertThat(thrown.getMessage(), containsString("Only one chart source"));
    }

    @Test
    void shouldRejectARepositoryWithoutAChartName() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> ChartSource.builder()
                .repository(Property.ofValue("https://helm.github.io/examples"))
                .build()
                .resolve(runContext())
        );

        assertThat(thrown.getMessage(), containsString("`name` is required"));
    }
}
