package io.kestra.plugin.helm.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.helm.models.ReleaseResource;

public final class ManifestService {
    private static final ObjectMapper YAML = JacksonMapper.ofYaml();

    public static final List<String> DEFAULT_RESOURCE_KINDS = List.of(
        "Deployment",
        "StatefulSet",
        "DaemonSet",
        "Service",
        "Ingress",
        "ConfigMap",
        "Secret",
        "PersistentVolumeClaim",
        "Job",
        "CronJob",
        "HorizontalPodAutoscaler",
        "ServiceAccount"
    );

    private ManifestService() {
    }

    public static List<ReleaseResource> parse(String manifest, String defaultNamespace, List<String> resourceKinds) {
        if (manifest == null || manifest.isBlank()) {
            return List.of();
        }

        Set<String> kinds = Set.copyOf(resourceKinds == null ? DEFAULT_RESOURCE_KINDS : resourceKinds);
        List<ReleaseResource> resources = new ArrayList<>();

        try (var parser = YAML.getFactory().createParser(manifest)) {
            MappingIterator<Map<String, Object>> documents = YAML.readValues(parser, new TypeReference<>() {
            });

            while (documents.hasNextValue()) {
                Map<String, Object> document = documents.nextValue();
                if (document == null) {
                    continue;
                }

                Object kind = document.get("kind");
                if (!(kind instanceof String kindName) || !kinds.contains(kindName)) {
                    continue;
                }

                if (!(document.get("metadata") instanceof Map<?, ?> metadata)) {
                    continue;
                }

                Object name = metadata.get("name");
                if (!(name instanceof String resourceName)) {
                    continue;
                }

                Object namespace = metadata.get("namespace");
                resources.add(new ReleaseResource(
                    kindName,
                    resourceName,
                    namespace instanceof String ns ? ns : defaultNamespace
                ));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse the rendered Helm manifest: " + e.getMessage(), e);
        }

        return resources;
    }
}
