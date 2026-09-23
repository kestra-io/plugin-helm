package io.kestra.plugin.helm.services;

import java.util.List;
import java.util.Map;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.assets.Asset;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.AssetEmitter;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.helm.models.AssetFailureBehavior;
import io.kestra.plugin.helm.models.ReleaseResource;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@KestraTest
class AssetServiceTest {
    @Inject
    private RunContextFactory runContextFactory;

    private static AssetService.Descriptor descriptor(boolean deleted) {
        return new AssetService.Descriptor(
            "prod-eu",
            "europe-west1",
            "production",
            "nginx",
            "web",
            7,
            "nginx",
            "15.4.2",
            "1.25.3",
            "deployed",
            "https://charts.bitnami.com/bitnami/nginx:15.4.2",
            List.of("prod/nginx/values.yaml"),
            List.of(new ReleaseResource("Deployment", "nginx", "web")),
            deleted
        );
    }

    @Test
    void shouldCarryEveryMetadataKeyTheIssueRequires() {
        Map<String, Object> metadata = AssetService.releaseAsset(descriptor(false)).getMetadata();

        assertThat(metadata, hasEntry("kubernetesNamespace", "web"));
        assertThat(metadata, hasEntry("cluster", "prod-eu"));
        assertThat(metadata, hasEntry("region", "europe-west1"));
        assertThat(metadata, hasEntry("environment", "production"));
        assertThat(metadata, hasEntry("release", "nginx"));
        assertThat(metadata, hasEntry("revision", 7));
        assertThat(metadata, hasEntry("chart", "nginx"));
        assertThat(metadata, hasEntry("chartVersion", "15.4.2"));
        assertThat(metadata, hasEntry("appVersion", "1.25.3"));
        assertThat(metadata, hasEntry("status", "deployed"));
        assertThat(metadata, hasEntry("valuesSource", "prod/nginx/values.yaml"));
    }

    @Test
    void shouldOmitMetadataKeysWithNoValueRatherThanStoringNulls() {
        AssetService.Descriptor sparse = new AssetService.Descriptor(
            null, null, null, "nginx", "web", null, null, null, null, null, null, null, List.of(), false
        );

        Map<String, Object> metadata = AssetService.releaseAsset(sparse).getMetadata();

        assertThat(metadata, hasEntry("release", "nginx"));
        assertThat(metadata, not(hasKey("cluster")));
        assertThat(metadata, not(hasKey("region")));
        assertThat(metadata, not(hasKey("revision")));
        assertThat(metadata, not(hasKey("valuesSource")));
    }

    @Test
    void shouldUseTheIssuesAssetTypesAndAReadableDisplayName() {
        Asset release = AssetService.releaseAsset(descriptor(false));
        Asset resource = AssetService.resourceAsset(descriptor(false), new ReleaseResource("Deployment", "nginx", "web"));

        assertThat(release.getType(), is(AssetService.RELEASE_TYPE));
        assertThat(release.getDisplayName(), is("nginx"));
        assertThat(resource.getType(), is(AssetService.RESOURCE_TYPE));
        assertThat(resource.getDisplayName(), is("Deployment/nginx"));
    }

    @Test
    void shouldDistinguishReleasesOfTheSameNameOnDifferentClustersAndNamespaces() {
        // The asset id is what prevents two unrelated releases collapsing into one catalog entry.
        AssetService.Descriptor other = new AssetService.Descriptor(
            "staging", null, null, "nginx", "web", 1, null, null, null, null, null, null, List.of(), false
        );

        assertThat(
            AssetService.releaseAsset(descriptor(false)).getId(),
            not(is(AssetService.releaseAsset(other).getId()))
        );
    }

    @Test
    void shouldPreferTheResourceNamespaceOverTheReleaseNamespace() {
        Asset resource = AssetService.resourceAsset(
            descriptor(false),
            new ReleaseResource("Service", "nginx", "other-ns")
        );

        assertThat(resource.getMetadata(), hasEntry("kubernetesNamespace", "other-ns"));
    }

    @Test
    void shouldMarkAssetsDeletedForAnUninstall() {
        assertThat(AssetService.releaseAsset(descriptor(true)).isDeleted(), is(true));
        assertThat(
            AssetService.resourceAsset(descriptor(true), new ReleaseResource("Deployment", "nginx", "web")).isDeleted(),
            is(true)
        );
        assertThat(AssetService.releaseAsset(descriptor(false)).isDeleted(), is(false));
    }

    @Test
    void shouldReplaceCharactersTheAssetIdPatternRejects() {
        // Asset ids must match ^[a-zA-Z0-9][a-zA-Z0-9._:-]*
        assertThat(AssetService.id("github.com/acme/repo@main"), is("github.com-acme-repo-main"));
        assertThat(AssetService.id("prod-eu:web:nginx"), is("prod-eu:web:nginx"));
    }

    @Test
    void shouldPrefixIdsThatWouldOtherwiseStartWithASeparator() {
        assertThat(AssetService.id(":leading-colon"), is("x:leading-colon"));
        assertThat(AssetService.id("/slash"), is("x-slash"));
    }

    @Test
    void shouldTruncateIdsToTheLengthTheAssetModelAccepts() {
        String id = AssetService.id("a".repeat(400));

        assertThat(id.length(), is(lessThanOrEqualTo(150)));
    }

    @Test
    void shouldDegradeQuietlyWhenAssetsAreUnavailable() {
        // OSS returns an emitter that always throws; a deploy must not fail because of it.
        assertDoesNotThrow(() ->
            AssetService.emit(runContextFactory.of(), descriptor(false), AssetFailureBehavior.WARN)
        );
        assertDoesNotThrow(() ->
            AssetService.emit(runContextFactory.of(), descriptor(false), AssetFailureBehavior.FAIL)
        );
    }

    @Test
    void shouldNotCollideWhenSegmentsContainTheJoinSeparator() {
        // "prod:eu" + "web" and "prod" + "eu:web" must not produce the same id.
        AssetService.Descriptor clusterHasColon = new AssetService.Descriptor(
            "prod:eu", null, null, "nginx", "web", 1, null, null, null, null, null, null, List.of(), false
        );
        AssetService.Descriptor namespaceHasColon = new AssetService.Descriptor(
            "prod", null, null, "nginx", "eu:web", 1, null, null, null, null, null, null, List.of(), false
        );

        assertThat(
            AssetService.releaseAsset(clusterHasColon).getId(),
            not(is(AssetService.releaseAsset(namespaceHasColon).getId()))
        );
    }

    @Test
    void shouldNotClaimSuccessWhenEmissionIsSilentlyDropped() throws Exception {
        // On EE with assets.enableAuto unset, emit() is a no-op that neither throws nor records
        // anything. Stubbed because the open-source emitter throws instead of dropping quietly.
        AssetEmitter dropping = mock(AssetEmitter.class);
        when(dropping.emitted()).thenReturn(List.of());

        Logger logger = mock(Logger.class);

        RunContext runContext = mock(RunContext.class);
        when(runContext.assets()).thenReturn(dropping);
        when(runContext.logger()).thenReturn(logger);

        AssetService.emit(runContext, descriptor(false), AssetFailureBehavior.WARN);

        verify(dropping, times(2)).emit(any());
        verify(logger, never()).info(anyString(), any(), any());
        verify(logger).debug(contains("assets.enableAuto"), eq("nginx"));
    }

    @Test
    void shouldReportSuccessOnlyWhenTheEmitterActuallyGrew() throws Exception {
        AssetEmitter recording = mock(AssetEmitter.class);
        when(recording.emitted())
            .thenReturn(List.of())
            .thenReturn(List.of(new AssetEmit(List.of(), List.of()), new AssetEmit(List.of(), List.of())));

        Logger logger = mock(Logger.class);

        RunContext runContext = mock(RunContext.class);
        when(runContext.assets()).thenReturn(recording);
        when(runContext.logger()).thenReturn(logger);

        AssetService.emit(runContext, descriptor(false), AssetFailureBehavior.WARN);

        verify(logger).info(contains("Emitted"), any(), any());
    }
}
