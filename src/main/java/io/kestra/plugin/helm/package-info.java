@PluginSubGroup(
    title = "Helm",
    description = "Deploy and manage Kubernetes applications from Helm charts.\n\n" +
        "Wraps the `helm` CLI as orchestrated tasks, so a deployment becomes one step in a broader flow " +
        "and each deployed release is registered as a queryable, lineage-tracked Asset.",
    categories = {PluginSubGroup.PluginCategory.INFRASTRUCTURE, PluginSubGroup.PluginCategory.CLOUD}
)
package io.kestra.plugin.helm;

import io.kestra.core.models.annotations.PluginSubGroup;
