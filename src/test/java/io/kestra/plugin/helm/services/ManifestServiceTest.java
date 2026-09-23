package io.kestra.plugin.helm.services;

import java.util.List;

import io.kestra.plugin.helm.models.ReleaseResource;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

class ManifestServiceTest {
    private static final String MULTI_DOC = """
        ---
        # Source: hello/templates/serviceaccount.yaml
        apiVersion: v1
        kind: ServiceAccount
        metadata:
          name: hw-hello-world
        ---
        # Source: hello/templates/service.yaml
        apiVersion: v1
        kind: Service
        metadata:
          name: hw-hello-world
          namespace: explicit-ns
        spec:
          ports:
            - port: 80
        ---
        # Source: hello/templates/deployment.yaml
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: hw-hello-world
        spec:
          replicas: 1
        """;

    @Test
    void shouldParseEveryDocumentOfAMultiDocumentManifest() {
        List<ReleaseResource> resources = ManifestService.parse(MULTI_DOC, "default", null);

        assertThat(resources, hasSize(3));
        assertThat(
            resources.stream().map(ReleaseResource::kind).toList(),
            contains("ServiceAccount", "Service", "Deployment")
        );
    }

    @Test
    void shouldPreferTheManifestNamespaceOverTheFallback() {
        List<ReleaseResource> resources = ManifestService.parse(MULTI_DOC, "fallback-ns", null);

        ReleaseResource service = resources.stream()
            .filter(r -> "Service".equals(r.kind()))
            .findFirst()
            .orElseThrow();
        ReleaseResource deployment = resources.stream()
            .filter(r -> "Deployment".equals(r.kind()))
            .findFirst()
            .orElseThrow();

        assertThat(service.namespace(), is("explicit-ns"));
        assertThat(deployment.namespace(), is("fallback-ns"));
    }

    @Test
    void shouldKeepOnlyTheRequestedKinds() {
        List<ReleaseResource> resources = ManifestService.parse(MULTI_DOC, "default", List.of("Deployment"));

        assertThat(resources, hasSize(1));
        assertThat(resources.getFirst().kind(), is("Deployment"));
    }

    @Test
    void shouldDropKindsOutsideTheDefaultList() {
        String manifest = """
            apiVersion: policy/v1
            kind: PodDisruptionBudget
            metadata:
              name: not-registered
            """;

        assertThat(ManifestService.parse(manifest, "default", null), is(empty()));
    }

    @Test
    void shouldReturnNothingForBlankOrNullInput() {
        assertThat(ManifestService.parse(null, "default", null), is(empty()));
        assertThat(ManifestService.parse("", "default", null), is(empty()));
        assertThat(ManifestService.parse("   \n  ", "default", null), is(empty()));
    }

    @Test
    void shouldSkipDocumentsWithoutUsableMetadata() {
        String manifest = """
            ---
            apiVersion: v1
            kind: Service
            ---
            apiVersion: v1
            kind: Service
            metadata:
              labels:
                a: b
            ---
            apiVersion: v1
            kind: Service
            metadata:
              name: keeper
            """;

        List<ReleaseResource> resources = ManifestService.parse(manifest, "default", null);

        assertThat(resources, hasSize(1));
        assertThat(resources.getFirst().name(), is("keeper"));
    }

    @Test
    void shouldCoverTheKindsTheIssueRequires() {
        assertThat(
            ManifestService.DEFAULT_RESOURCE_KINDS,
            contains(
                "Deployment", "StatefulSet", "DaemonSet", "Service", "Ingress", "ConfigMap",
                "Secret", "PersistentVolumeClaim", "Job", "CronJob", "HorizontalPodAutoscaler",
                "ServiceAccount"
            )
        );
    }
}
