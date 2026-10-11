package io.kestra.plugin.helm.services;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.AuthInfo;
import io.fabric8.kubernetes.api.model.Cluster;
import io.fabric8.kubernetes.api.model.NamedAuthInfo;
import io.fabric8.kubernetes.api.model.NamedCluster;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.internal.KubeConfigUtils;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.kubernetes.shared.models.Connection;

public final class KubeConfigService {
    private static final String CLUSTER = "kestra";
    private static final String USER = "kestra";
    private static final String CONTEXT = "kestra";

    private KubeConfigService() {
    }

    public record ClusterAccess(String kubeconfig, Config connectionConfig, String cluster) {
    }

    public static ClusterAccess resolve(
        RunContext runContext,
        Connection connection,
        Property<Boolean> inheritClusterConfig,
        Property<String> kubeconfig,
        Property<String> cluster
    ) throws Exception {
        String rKubeconfig = runContext.render(kubeconfig).as(String.class).orElse(null);

        if (rKubeconfig != null && connection != null) {
            throw new IllegalArgumentException("Set either `connection` or `kubeconfig`, not both.");
        }

        Config connectionConfig = connection == null ? null : connection.toConfig(
            runContext,
            runContext.render(inheritClusterConfig).as(Boolean.class).orElse(false)
        );

        String rCluster = runContext.render(cluster).as(String.class)
            .orElseGet(() -> connectionConfig == null ? null : clusterName(connectionConfig));

        return new ClusterAccess(rKubeconfig, connectionConfig, rCluster);
    }

    // Loading a kubeconfig in the JVM runs `exec` and `auth-provider` plugins and reads local files
    // on the worker, which the Helm container would otherwise have isolated.
    public static void requireInlineCredentials(String kubeconfig) {
        io.fabric8.kubernetes.api.model.Config parsed = KubeConfigUtils.parseConfigFromString(kubeconfig);
        List<String> rejected = new ArrayList<>();

        for (NamedAuthInfo named : listOrEmpty(parsed.getUsers())) {
            AuthInfo user = named.getUser();
            if (user == null) {
                continue;
            }

            String prefix = "users[" + named.getName() + "].user.";
            if (user.getExec() != null) {
                rejected.add(prefix + "exec");
            }
            if (user.getAuthProvider() != null) {
                rejected.add(prefix + "auth-provider");
            }
            if (user.getTokenFile() != null) {
                rejected.add(prefix + "tokenFile");
            }
            if (user.getClientCertificate() != null) {
                rejected.add(prefix + "client-certificate");
            }
            if (user.getClientKey() != null) {
                rejected.add(prefix + "client-key");
            }
        }

        for (NamedCluster named : listOrEmpty(parsed.getClusters())) {
            Cluster cluster = named.getCluster();
            if (cluster != null && cluster.getCertificateAuthority() != null) {
                rejected.add("clusters[" + named.getName() + "].cluster.certificate-authority");
            }
        }

        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException(
                "`kubeconfig` must carry its credentials inline: " + String.join(", ", rejected) + " "
                    + "would run a command or read a file on the Kestra worker. Use the `*-data` fields and a `token`, "
                    + "or `connection` with an `oauthTokenProvider` for short-lived cloud credentials."
            );
        }
    }

    private static <T> List<T> listOrEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    public static Map<String, Object> toKubeConfig(Config config, String namespace) {
        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("server", config.getMasterUrl());

        String caCertData = normalize(config.getCaCertData(), "caCertData");
        if (caCertData != null) {
            cluster.put("certificate-authority-data", caCertData);
        } else if (config.isTrustCerts()) {
            cluster.put("insecure-skip-tls-verify", true);
        }

        Map<String, Object> user = new LinkedHashMap<>();
        String token = config.getOauthToken() != null ? config.getOauthToken() : config.getAutoOAuthToken();
        if (token == null && config.getOauthTokenProvider() != null) {
            token = config.getOauthTokenProvider().getToken();
        }
        if (token != null && !token.isBlank()) {
            user.put("token", token);
        }

        String clientCertData = normalize(config.getClientCertData(), "clientCertData");
        if (clientCertData != null) {
            user.put("client-certificate-data", clientCertData);
        }

        String clientKeyData = normalize(config.getClientKeyData(), "clientKeyData");
        if (clientKeyData != null) {
            user.put("client-key-data", clientKeyData);
        }

        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            user.put("username", config.getUsername());
        }
        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            user.put("password", config.getPassword());
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("cluster", CLUSTER);
        context.put("user", USER);
        String resolvedNamespace = namespace != null ? namespace : config.getNamespace();
        if (resolvedNamespace != null && !resolvedNamespace.isBlank()) {
            context.put("namespace", resolvedNamespace);
        }

        Map<String, Object> kubeConfig = new LinkedHashMap<>();
        kubeConfig.put("apiVersion", "v1");
        kubeConfig.put("kind", "Config");
        kubeConfig.put("clusters", List.of(Map.of("name", CLUSTER, "cluster", cluster)));
        kubeConfig.put("users", List.of(Map.of("name", USER, "user", user)));
        kubeConfig.put("contexts", List.of(Map.of("name", CONTEXT, "context", context)));
        kubeConfig.put("current-context", CONTEXT);

        return kubeConfig;
    }

    public static String clusterName(Config config) {
        String masterUrl = config.getMasterUrl();
        if (masterUrl == null || masterUrl.isBlank()) {
            return null;
        }

        return masterUrl
            .replaceFirst("^[a-zA-Z0-9+.-]+://", "")
            .replaceFirst("/.*$", "")
            .replaceFirst(":\\d+$", "");
    }

    private static String normalize(String data, String field) {
        if (data == null || data.isBlank()) {
            return null;
        }

        if (data.startsWith("-----BEGIN")) {
            return Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8));
        }

        String compact = data.replaceAll("\\s", "");
        try {
            Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "`connection." + field + "` is neither PEM (starting with `-----BEGIN`) nor valid base64, so it cannot be written to a kubeconfig.",
                e
            );
        }

        return compact;
    }
}
