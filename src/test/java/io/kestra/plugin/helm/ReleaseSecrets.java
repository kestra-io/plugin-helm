package io.kestra.plugin.helm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

public final class ReleaseSecrets {
    private ReleaseSecrets() {
    }

    public static Secret release(String namespace, String name, int revision, String status) {
        return release(namespace, name, revision, status, Instant.now(), true);
    }

    public static Secret release(String namespace, String name, int revision, String status, Instant createdAt, boolean gzip) {
        return withBody(namespace, name, revision, status, createdAt, encode(body(namespace, name, revision, status), gzip));
    }

    public static Secret withBody(String namespace, String name, int revision, String status, Instant createdAt, String helmEncoded) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("owner", "helm");
        labels.put("name", name);
        labels.put("status", status);
        labels.put("version", String.valueOf(revision));
        labels.put("createdAt", String.valueOf(createdAt.getEpochSecond()));

        return new SecretBuilder()
            .withNewMetadata()
                .withName("sh.helm.release.v1." + name + ".v" + revision)
                .withNamespace(namespace)
                .withLabels(labels)
            .endMetadata()
            .withType("helm.sh/release.v1")
            .addToData("release", Base64.getEncoder().encodeToString(helmEncoded.getBytes(StandardCharsets.UTF_8)))
            .build();
    }

    public static String body(String namespace, String name, int revision, String status) {
        return """
            {
              "name": "%s",
              "namespace": "%s",
              "version": %d,
              "info": {
                "first_deployed": "2026-10-01T10:00:00Z",
                "last_deployed": "2026-10-05T10:00:00Z",
                "status": "%s",
                "description": "Upgrade complete"
              },
              "chart": {"metadata": {"name": "hello", "version": "0.1.0", "appVersion": "1.0.0"}},
              "config": {"password": "do-not-leak"}
            }
            """.formatted(name, namespace, revision, status);
    }

    public static String encode(String json, boolean gzip) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        if (gzip) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream stream = new GZIPOutputStream(out)) {
                stream.write(bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            bytes = out.toByteArray();
        }

        return Base64.getEncoder().encodeToString(bytes);
    }
}
