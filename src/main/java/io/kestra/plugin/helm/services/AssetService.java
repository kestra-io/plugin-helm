package io.kestra.plugin.helm.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.kestra.core.models.assets.Asset;
import io.kestra.core.models.assets.AssetIdentifier;
import io.kestra.core.models.assets.Custom;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.helm.models.AssetFailureBehavior;
import io.kestra.plugin.helm.models.ReleaseResource;

public final class AssetService {
    public static final String RELEASE_TYPE = "io.kestra.plugin.ee.assets.HelmRelease";
    public static final String RESOURCE_TYPE = "io.kestra.plugin.ee.assets.KubernetesResource";
    public static final String FILE_TYPE = "io.kestra.plugin.ee.assets.File";
    public static final String CHART_TYPE = "io.kestra.plugin.helm.assets.Chart";

    private AssetService() {
    }

    public record Descriptor(
        String cluster,
        String region,
        String environment,
        String releaseName,
        String namespace,
        Integer revision,
        String chart,
        String chartVersion,
        String appVersion,
        String status,
        String chartReference,
        List<String> valuesReferences,
        List<ReleaseResource> resources,
        boolean deleted
    ) {
    }

    public static void emit(RunContext runContext, Descriptor descriptor, AssetFailureBehavior behavior) throws Exception {
        try {
            var emitter = runContext.assets();
            int before = emitter.emitted().size();

            Asset release = releaseAsset(descriptor);

            List<AssetIdentifier> inputs = new ArrayList<>();
            if (descriptor.chartReference() != null) {
                inputs.add(new AssetIdentifier(null, null, id(descriptor.chartReference()), CHART_TYPE));
            }
            if (descriptor.valuesReferences() != null) {
                descriptor.valuesReferences().stream()
                    .map(reference -> new AssetIdentifier(null, null, id(reference), FILE_TYPE))
                    .forEach(inputs::add);
            }

            emitter.emit(new AssetEmit(inputs, List.of(release)));

            List<Asset> resources = descriptor.resources() == null ? List.of() : descriptor.resources().stream()
                .map(resource -> resourceAsset(descriptor, resource))
                .toList();

            if (!resources.isEmpty()) {
                emitter.emit(new AssetEmit(List.of(AssetIdentifier.of(release)), resources));
            }

            // emit() is a silent no-op unless assets.enableAuto is set on the task (the flag backs the
            // whole AssetEmitter, not only auto-detection of dynamically-referenced assets), so check
            // emitted() actually grew before claiming success.
            if (emitter.emitted().size() > before) {
                runContext.logger().info(
                    "Emitted {} Helm asset(s) for release '{}'",
                    resources.size() + 1,
                    descriptor.releaseName()
                );
            } else {
                runContext.logger().debug(
                    "Helm assets for release '{}' were not recorded — set `assets.enableAuto: true` on this task to register them",
                    descriptor.releaseName()
                );
            }
        } catch (UnsupportedOperationException e) {
            runContext.logger().debug("Asset emission is not supported in this edition, skipping.");
        } catch (Exception e) {
            if (behavior == AssetFailureBehavior.FAIL) {
                throw e;
            }
            runContext.logger().warn("Unable to emit Helm assets for release '{}'", descriptor.releaseName(), e);
        }
    }

    static Asset releaseAsset(Descriptor descriptor) {
        Map<String, Object> metadata = baseMetadata(descriptor);
        put(metadata, "valuesSource", descriptor.valuesReferences() == null || descriptor.valuesReferences().isEmpty()
            ? null
            : String.join(",", descriptor.valuesReferences()));

        Custom asset = Custom.builder()
            .id(id(join(descriptor.cluster(), descriptor.namespace(), descriptor.releaseName())))
            .type(RELEASE_TYPE)
            .displayName(descriptor.releaseName())
            .metadata(metadata)
            .build();

        return descriptor.deleted() ? asset.toDeleted() : asset;
    }

    static Asset resourceAsset(Descriptor descriptor, ReleaseResource resource) {
        String namespace = resource.namespace() != null ? resource.namespace() : descriptor.namespace();

        Map<String, Object> metadata = baseMetadata(descriptor);
        put(metadata, "kind", resource.kind());
        put(metadata, "name", resource.name());
        put(metadata, "kubernetesNamespace", namespace);

        Custom asset = Custom.builder()
            .id(id(join(descriptor.cluster(), namespace, resource.kind(), resource.name())))
            .type(RESOURCE_TYPE)
            .displayName(resource.kind() + "/" + resource.name())
            .metadata(metadata)
            .build();

        return descriptor.deleted() ? asset.toDeleted() : asset;
    }

    private static Map<String, Object> baseMetadata(Descriptor descriptor) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(metadata, "kubernetesNamespace", descriptor.namespace());
        put(metadata, "cluster", descriptor.cluster());
        put(metadata, "region", descriptor.region());
        put(metadata, "environment", descriptor.environment());
        put(metadata, "release", descriptor.releaseName());
        put(metadata, "revision", descriptor.revision());
        put(metadata, "chart", descriptor.chart());
        put(metadata, "chartVersion", descriptor.chartVersion());
        put(metadata, "appVersion", descriptor.appVersion());
        put(metadata, "status", descriptor.status());

        return metadata;
    }

    private static void put(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    // Each segment is length-prefixed because the parts are free text: joining "prod:eu" + "web"
    // and "prod" + "eu:web" on a bare separator yields the same id, so two unrelated releases
    // would share one catalog entry.
    private static String join(String... parts) {
        String joined = Stream.of(parts)
            .filter(part -> part != null && !part.isBlank())
            .map(part -> part.length() + "." + part)
            .reduce((left, right) -> left + ":" + right)
            .orElse("");

        return joined.isEmpty() ? "unknown" : joined;
    }

    static String id(String raw) {
        String sanitized = raw.replaceAll("[^a-zA-Z0-9._:-]", "-");

        if (!sanitized.isEmpty() && !Character.isLetterOrDigit(sanitized.charAt(0))) {
            sanitized = "x" + sanitized;
        }

        return sanitized.length() > 150 ? sanitized.substring(0, 150) : sanitized;
    }
}
