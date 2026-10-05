package io.kestra.plugin.helm.services;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.model.Secret;

import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.helm.models.Release;

public final class ReleaseStorageService {
    public static final String OWNER_LABEL = "owner";
    public static final String OWNER = "helm";
    public static final String NAME_LABEL = "name";
    public static final String STATUS_LABEL = "status";
    public static final String VERSION_LABEL = "version";

    private static final String CREATED_AT_LABEL = "createdAt";
    private static final String MODIFIED_AT_LABEL = "modifiedAt";
    private static final String RELEASE_KEY = "release";
    private static final byte[] GZIP_MAGIC = {0x1f, (byte) 0x8b, 0x08};

    private static final ObjectMapper JSON = JacksonMapper.ofJson();

    private ReleaseStorageService() {
    }

    public static List<Secret> latestRevisions(Collection<Secret> secrets) {
        Map<String, Secret> latest = new LinkedHashMap<>();

        for (Secret secret : secrets) {
            String name = label(secret, NAME_LABEL);
            if (name == null || revision(secret) == null) {
                continue;
            }

            latest.merge(
                secret.getMetadata().getNamespace() + "/" + name,
                secret,
                (current, candidate) -> revision(candidate) > revision(current) ? candidate : current
            );
        }

        return latest.values().stream()
            .sorted(Comparator
                .comparing((Secret secret) -> secret.getMetadata().getNamespace())
                .thenComparing(secret -> label(secret, NAME_LABEL)))
            .toList();
    }

    public static String releaseName(Secret secret) {
        return label(secret, NAME_LABEL);
    }

    public static String status(Secret secret) {
        return label(secret, STATUS_LABEL);
    }

    public static Integer revision(Secret secret) {
        String value = label(secret, VERSION_LABEL);
        if (value == null) {
            return null;
        }

        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // A pending revision has no modifiedAt yet, so createdAt is when the stuck operation started.
    public static Instant changedAt(Secret secret) {
        Instant modified = epochSeconds(label(secret, MODIFIED_AT_LABEL));
        if (modified != null) {
            return modified;
        }

        Instant created = epochSeconds(label(secret, CREATED_AT_LABEL));
        if (created != null) {
            return created;
        }

        String timestamp = secret.getMetadata().getCreationTimestamp();
        return timestamp == null ? null : OffsetDateTime.parse(timestamp).toInstant();
    }

    // Base64 twice: once by the Kubernetes API, once by Helm itself.
    public static Release decode(Secret secret) throws IOException {
        String data = secret.getData() == null ? null : secret.getData().get(RELEASE_KEY);
        if (data == null) {
            throw new IOException("Secret '" + secret.getMetadata().getName() + "' has no `release` key.");
        }

        byte[] body = Base64.getDecoder().decode(Base64.getDecoder().decode(data));

        if (startsWithGzipMagic(body)) {
            try (InputStream stream = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = stream.readAllBytes();
            }
        }

        return JSON.readValue(body, Release.class);
    }

    private static boolean startsWithGzipMagic(byte[] body) {
        if (body.length <= GZIP_MAGIC.length) {
            return false;
        }

        for (int i = 0; i < GZIP_MAGIC.length; i++) {
            if (body[i] != GZIP_MAGIC[i]) {
                return false;
            }
        }

        return true;
    }

    private static Instant epochSeconds(String value) {
        if (value == null) {
            return null;
        }

        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String label(Secret secret, String key) {
        Map<String, String> labels = secret.getMetadata().getLabels();
        return labels == null ? null : labels.get(key);
    }
}
