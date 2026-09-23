package io.kestra.plugin.helm.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(
    title = "A Kubernetes resource managed by a Helm release"
)
public record ReleaseResource(
    @Schema(title = "Resource kind, e.g. `Deployment`") String kind,
    @Schema(title = "Resource name") String name,
    @Schema(title = "Namespace the resource belongs to") String namespace
) {
}
