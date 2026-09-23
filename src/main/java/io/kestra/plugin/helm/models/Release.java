package io.kestra.plugin.helm.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Release(
    String name,
    String namespace,
    Integer version,
    Info info,
    Chart chart,
    String manifest
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Info(
        @JsonProperty("first_deployed") String firstDeployed,
        @JsonProperty("last_deployed") String lastDeployed,
        String status,
        String notes,
        String description
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chart(Metadata metadata) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Metadata(String name, String version, String appVersion) {
        }
    }

    public String chartName() {
        return chart == null || chart.metadata() == null ? null : chart.metadata().name();
    }

    public String chartVersion() {
        return chart == null || chart.metadata() == null ? null : chart.metadata().version();
    }

    public String appVersion() {
        return chart == null || chart.metadata() == null ? null : chart.metadata().appVersion();
    }

    public String status() {
        return info == null ? null : info.status();
    }

    public String notes() {
        return info == null ? null : info.notes();
    }

    public String firstDeployed() {
        return info == null ? null : info.firstDeployed();
    }

    public String lastDeployed() {
        return info == null ? null : info.lastDeployed();
    }
}
