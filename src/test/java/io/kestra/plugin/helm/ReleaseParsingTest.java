package io.kestra.plugin.helm;

import io.kestra.plugin.helm.models.Release;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReleaseParsingTest {
    private static final String RELEASE_JSON = """
        {
          "name": "podinfo",
          "namespace": "helm-test",
          "version": 2,
          "info": {
            "first_deployed": "2026-09-18T11:55:55.744305883Z",
            "last_deployed": "2026-09-18T12:01:44.211803710Z",
            "status": "deployed",
            "notes": "1. Get the application URL"
          },
          "chart": {
            "metadata": {
              "name": "podinfo",
              "version": "6.15.0",
              "appVersion": "6.15.0"
            }
          },
          "manifest": "apiVersion: v1\\nkind: Service\\n"
        }
        """;

    @Test
    void shouldMapEveryFieldTheOutputsExpose() throws Exception {
        Release release = AbstractHelm.parseRelease(RELEASE_JSON, "release.json");

        assertThat(release.name(), is("podinfo"));
        assertThat(release.namespace(), is("helm-test"));
        assertThat(release.version(), is(2));
        assertThat(release.status(), is("deployed"));
        assertThat(release.chartName(), is("podinfo"));
        assertThat(release.chartVersion(), is("6.15.0"));
        assertThat(release.appVersion(), is("6.15.0"));
        assertThat(release.firstDeployed(), is("2026-09-18T11:55:55.744305883Z"));
        assertThat(release.lastDeployed(), is("2026-09-18T12:01:44.211803710Z"));
        assertThat(release.notes(), containsString("Get the application URL"));
        assertThat(release.manifest(), containsString("kind: Service"));
    }

    @Test
    void shouldSkipThePullBannerHelmPrintsBeforeOciJson() throws Exception {
        // `helm upgrade` writes these two lines to stdout, not stderr, when the chart comes
        // from an OCI registry, so the captured file is not valid JSON on its own.
        String contaminated = """
            Pulled: ghcr.io/stefanprodan/charts/podinfo:6.15.0
            Digest: sha256:ff3d3e14728f75476ed4d43c14f80d52d81d36bc16906843463d464c6146f0d8
            """ + RELEASE_JSON;

        Release release = AbstractHelm.parseRelease(contaminated, "release.json");

        assertThat(release.name(), is("podinfo"));
        assertThat(release.chartVersion(), is("6.15.0"));
    }

    @Test
    void shouldFailWithTheRawOutputWhenHelmPrintsNoJsonAtAll() {
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> AbstractHelm.parseRelease("Error: release not found", "release.json")
        );

        assertThat(thrown.getMessage(), containsString("release.json"));
        assertThat(thrown.getMessage(), containsString("release not found"));
    }

    @Test
    void shouldTolerateAReleaseMissingOptionalSections() throws Exception {
        Release release = AbstractHelm.parseRelease("{\"name\":\"bare\"}", "release.json");

        assertThat(release.name(), is("bare"));
        assertThat(release.chartName(), is(nullValue()));
        assertThat(release.chartVersion(), is(nullValue()));
        assertThat(release.appVersion(), is(nullValue()));
        assertThat(release.status(), is(nullValue()));
        assertThat(release.notes(), is(nullValue()));
        assertThat(release.firstDeployed(), is(nullValue()));
    }

    @Test
    void shouldIgnoreFieldsTheModelDoesNotDeclare() throws Exception {
        // Helm adds fields across releases; an unknown one must not fail a deploy.
        Release release = AbstractHelm.parseRelease(
            "{\"name\":\"fwd\",\"apply_method\":\"ssa\",\"hooks\":[],\"config\":{\"a\":1}}",
            "release.json"
        );

        assertThat(release.name(), is("fwd"));
    }
}
