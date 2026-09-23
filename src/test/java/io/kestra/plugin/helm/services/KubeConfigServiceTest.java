package io.kestra.plugin.helm.services;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KubeConfigServiceTest {
    // Config.empty() rather than a bare ConfigBuilder: fabric8 auto-configures from the ambient
    // kubeconfig, which would leak the developer's own cluster CA into these assertions.

    private static final String PEM = "-----BEGIN CERTIFICATE-----\nMIIBkTCB+w==\n-----END CERTIFICATE-----";
    private static final String BASE64 = Base64.getEncoder().encodeToString("some-cert-bytes".getBytes(StandardCharsets.UTF_8));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cluster(Map<String, Object> kubeConfig) {
        return (Map<String, Object>) ((Map<String, Object>) ((List<Object>) kubeConfig.get("clusters")).getFirst()).get("cluster");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> user(Map<String, Object> kubeConfig) {
        return (Map<String, Object>) ((Map<String, Object>) ((List<Object>) kubeConfig.get("users")).getFirst()).get("user");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> context(Map<String, Object> kubeConfig) {
        return (Map<String, Object>) ((Map<String, Object>) ((List<Object>) kubeConfig.get("contexts")).getFirst()).get("context");
    }

    @Test
    void shouldRenderAUsableKubeConfigSkeleton() {
        Config config = new ConfigBuilder(Config.empty()).withMasterUrl("https://cluster.example.com:6443").build();

        Map<String, Object> kubeConfig = KubeConfigService.toKubeConfig(config, "web");

        assertThat(kubeConfig, hasEntry("apiVersion", "v1"));
        assertThat(kubeConfig, hasEntry("kind", "Config"));
        assertThat(kubeConfig, hasEntry("current-context", "kestra"));
        assertThat(cluster(kubeConfig), hasEntry("server", "https://cluster.example.com:6443/"));
        assertThat(context(kubeConfig), hasEntry("namespace", "web"));
    }

    @Test
    void shouldVerifyAgainstTheCertificateAuthorityWhenOneIsSupplied() {
        Config config = new ConfigBuilder(Config.empty())
            .withMasterUrl("https://cluster.example.com:6443")
            .withCaCertData(BASE64)
            .withTrustCerts(true)
            .build();

        Map<String, Object> cluster = cluster(KubeConfigService.toKubeConfig(config, null));

        // A CA must win over trustCerts: silently skipping verification would be a downgrade.
        assertThat(cluster, hasEntry("certificate-authority-data", BASE64));
        assertThat(cluster, not(hasKey("insecure-skip-tls-verify")));
    }

    @Test
    void shouldSkipVerificationOnlyWhenNoCertificateAuthorityIsAvailable() {
        Config config = new ConfigBuilder(Config.empty())
            .withMasterUrl("https://cluster.example.com:6443")
            .withTrustCerts(true)
            .build();

        assertThat(cluster(KubeConfigService.toKubeConfig(config, null)), hasEntry("insecure-skip-tls-verify", true));
    }

    @Test
    void shouldBase64EncodePemMaterialAndPassBase64Through() {
        Config config = new ConfigBuilder(Config.empty())
            .withMasterUrl("https://cluster.example.com:6443")
            .withCaCertData(PEM)
            .withClientCertData(BASE64)
            .build();

        Map<String, Object> kubeConfig = KubeConfigService.toKubeConfig(config, null);

        assertThat(
            cluster(kubeConfig),
            hasEntry("certificate-authority-data", Base64.getEncoder().encodeToString(PEM.getBytes(StandardCharsets.UTF_8)))
        );
        assertThat(user(kubeConfig), hasEntry("client-certificate-data", BASE64));
    }

    @Test
    void shouldStripWhitespaceFromWrappedBase64() {
        String wrapped = BASE64.substring(0, 4) + "\n  " + BASE64.substring(4);

        Config config = new ConfigBuilder(Config.empty())
            .withMasterUrl("https://cluster.example.com:6443")
            .withCaCertData(wrapped)
            .build();

        assertThat(cluster(KubeConfigService.toKubeConfig(config, null)), hasEntry("certificate-authority-data", BASE64));
    }

    @Test
    void shouldFailLoudlyOnCertificateDataThatIsNeitherPemNorBase64() {
        Config config = new ConfigBuilder(Config.empty())
            .withMasterUrl("https://cluster.example.com:6443")
            .withCaCertData("not valid base64 !!!")
            .build();

        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> KubeConfigService.toKubeConfig(config, null)
        );

        assertThat(thrown.getMessage().contains("caCertData"), is(true));
    }

    @Test
    void shouldCarryBearerTokenAndBasicCredentials() {
        Config config = new ConfigBuilder(Config.empty())
            .withMasterUrl("https://cluster.example.com:6443")
            .withOauthToken("a-token")
            .withUsername("alice")
            .withPassword("secret")
            .build();

        assertThat(user(KubeConfigService.toKubeConfig(config, null)), hasEntry("token", "a-token"));
        assertThat(user(KubeConfigService.toKubeConfig(config, null)), hasEntry("username", "alice"));
        assertThat(user(KubeConfigService.toKubeConfig(config, null)), hasEntry("password", "secret"));
    }

    @Test
    void shouldLeaveTheUserEmptyWhenNoCredentialsExist() {
        Config config = new ConfigBuilder(Config.empty()).withMasterUrl("https://cluster.example.com:6443").build();

        assertThat(user(KubeConfigService.toKubeConfig(config, null)), is(aMapWithSize(0)));
    }

    @Test
    void shouldDeriveTheClusterNameFromTheApiServerHost() {
        assertThat(
            KubeConfigService.clusterName(new ConfigBuilder(Config.empty()).withMasterUrl("https://prod-eu.k8s.example.com:6443/").build()),
            is("prod-eu.k8s.example.com")
        );
        assertThat(
            KubeConfigService.clusterName(new ConfigBuilder(Config.empty()).withMasterUrl("https://kestra-helm-control-plane:6443").build()),
            is("kestra-helm-control-plane")
        );
    }
}
