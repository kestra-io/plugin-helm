package io.kestra.plugin.helm.services;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import io.fabric8.kubernetes.api.model.Secret;

import io.kestra.plugin.helm.ReleaseSecrets;
import io.kestra.plugin.helm.models.Release;
import io.kestra.plugin.helm.models.ReleaseStatus;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReleaseStorageServiceTest {
    @Test
    void shouldKeepOnlyTheLatestRevisionOfEachRelease() {
        List<Secret> latest = ReleaseStorageService.latestRevisions(List.of(
            ReleaseSecrets.release("web", "nginx", 1, "superseded"),
            ReleaseSecrets.release("web", "nginx", 3, "deployed"),
            ReleaseSecrets.release("web", "nginx", 2, "failed"),
            ReleaseSecrets.release("api", "nginx", 1, "failed"),
            ReleaseSecrets.release("web", "redis", 10, "deployed"),
            ReleaseSecrets.release("web", "redis", 9, "superseded")
        ));

        assertThat(
            latest.stream().map(secret -> secret.getMetadata().getName()).toList(),
            contains("sh.helm.release.v1.nginx.v1", "sh.helm.release.v1.nginx.v3", "sh.helm.release.v1.redis.v10")
        );
        assertThat(latest.getFirst().getMetadata().getNamespace(), is("api"));
    }

    @Test
    void shouldDecodeAGzippedRelease() throws IOException {
        Release release = ReleaseStorageService.decode(ReleaseSecrets.release("web", "nginx", 4, "failed", Instant.now(), true));

        assertThat(release.name(), is("nginx"));
        assertThat(release.version(), is(4));
        assertThat(release.status(), is("failed"));
        assertThat(release.chartName(), is("hello"));
        assertThat(release.chartVersion(), is("0.1.0"));
        assertThat(release.appVersion(), is("1.0.0"));
        assertThat(release.description(), is("Upgrade complete"));
    }

    @Test
    void shouldDecodeAnUncompressedRelease() throws IOException {
        Release release = ReleaseStorageService.decode(ReleaseSecrets.release("web", "nginx", 1, "deployed", Instant.now(), false));

        assertThat(release.name(), is("nginx"));
        assertThat(release.chartName(), is("hello"));
    }

    @Test
    void shouldFailOnACorruptBody() {
        Secret secret = ReleaseSecrets.withBody("web", "nginx", 1, "deployed", Instant.now(), "bm90IGpzb24=");

        assertThrows(IOException.class, () -> ReleaseStorageService.decode(secret));
    }

    @Test
    void shouldPreferModifiedAtOverCreatedAt() {
        Secret secret = ReleaseSecrets.release("web", "nginx", 1, "deployed", Instant.ofEpochSecond(1_000), true);
        assertThat(ReleaseStorageService.changedAt(secret), is(Instant.ofEpochSecond(1_000)));

        secret.getMetadata().getLabels().put("modifiedAt", "2000");
        assertThat(ReleaseStorageService.changedAt(secret), is(Instant.ofEpochSecond(2_000)));
    }

    @Test
    void shouldMapHelmStatusValues() {
        assertThat(ReleaseStatus.fromHelm("pending-upgrade"), is(ReleaseStatus.PENDING_UPGRADE));
        assertThat(ReleaseStatus.fromHelm("deployed"), is(ReleaseStatus.DEPLOYED));
        assertThat(ReleaseStatus.fromHelm("something-new"), is(ReleaseStatus.UNKNOWN));
        assertThat(ReleaseStatus.fromHelm(null), is(ReleaseStatus.UNKNOWN));
        assertThat(ReleaseStatus.PENDING_ROLLBACK.helmValue(), is("pending-rollback"));
        assertThat(ReleaseStatus.PENDING_INSTALL.isPending(), is(true));
        assertThat(ReleaseStatus.FAILED.isPending(), is(false));
    }
}
