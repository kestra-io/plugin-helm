package io.kestra.plugin.helm;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.StatefulTriggerInterface;
import io.kestra.core.models.triggers.StatefulTriggerService;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.helm.models.Release;
import io.kestra.plugin.helm.models.ReleaseOutput;
import io.kestra.plugin.helm.models.ReleaseStatus;
import io.kestra.plugin.helm.services.ReleaseStorageService;
import io.kestra.plugin.kubernetes.shared.services.ClientService;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import static io.kestra.core.models.triggers.StatefulTriggerService.computeAndUpdateState;
import static io.kestra.core.models.triggers.StatefulTriggerService.defaultKey;
import static io.kestra.core.models.triggers.StatefulTriggerService.readState;
import static io.kestra.core.models.triggers.StatefulTriggerService.writeState;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow when a Helm release changes state",
    description = """
        Polls the Helm release records on a cluster and starts a flow when a release reaches a new revision or a new status, whoever made the change: a Kestra task, a CI pipeline, or someone running `helm` by hand.

        Reads Helm's release Secrets (`owner=helm`) through the Kubernetes API, so no Helm container runs on each poll. Only the latest revision of each release is considered, so a revision that failed long ago and has since been superseded does not fire again.

        The first evaluation treats every release already on the cluster as new; set `on: UPDATE` to react only to changes made after the trigger starts.

        Requires `list` permission on `secrets` in the watched namespaces, or cluster-wide with `allNamespaces`. Kubernetes RBAC cannot narrow that permission to Helm's Secrets, so prefer a namespace-scoped Role. Only releases stored with Helm's default `secret` storage driver are seen."""
)
@Plugin(
    examples = {
        @Example(
            title = "Alert on any release that fails in a namespace",
            full = true,
            code = """
                id: helm_failed_release_alert
                namespace: company.team

                tasks:
                  - id: each_release
                    type: io.kestra.plugin.core.flow.ForEach
                    values: "{{ trigger.releases }}"
                    tasks:
                      - id: slack
                        type: io.kestra.plugin.slack.SlackIncomingWebhook
                        url: "{{ secret('SLACK_WEBHOOK') }}"
                        payload: |
                          {
                            "text": "Helm release {{ fromJson(taskrun.value).releaseName }} failed at revision {{ fromJson(taskrun.value).revision }}: {{ fromJson(taskrun.value).description }}"
                          }

                triggers:
                  - id: failed_release
                    type: io.kestra.plugin.helm.ReleaseTrigger
                    namespace: web
                    statuses:
                      - FAILED
                    cluster: prod-eu
                    kubeconfig: "{{ secret('PROD_EU_KUBECONFIG') }}"
                """
        ),
        @Example(
            title = "Roll back a release stuck in pending-upgrade for 15 minutes",
            full = true,
            code = """
                id: helm_unstick_release
                namespace: company.team

                tasks:
                  - id: rollback
                    type: io.kestra.plugin.helm.Rollback
                    releaseName: "{{ trigger.releases[0].releaseName }}"
                    namespace: "{{ trigger.releases[0].namespace }}"
                    kubeconfig: "{{ secret('PROD_EU_KUBECONFIG') }}"

                triggers:
                  - id: stuck
                    type: io.kestra.plugin.helm.ReleaseTrigger
                    namespace: web
                    releaseName: nginx
                    statuses:
                      - PENDING_UPGRADE
                    stuckFor: PT15M
                    kubeconfig: "{{ secret('PROD_EU_KUBECONFIG') }}"
                """
        ),
        @Example(
            title = "Smoke-test every new revision that reaches deployed",
            full = true,
            code = """
                id: helm_post_deploy_smoke_test
                namespace: company.team

                tasks:
                  - id: each_release
                    type: io.kestra.plugin.core.flow.ForEach
                    values: "{{ trigger.releases }}"
                    tasks:
                      - id: healthz
                        type: io.kestra.plugin.core.http.Request
                        uri: "https://{{ fromJson(taskrun.value).releaseName }}.example.com/healthz"

                triggers:
                  - id: deployed
                    type: io.kestra.plugin.helm.ReleaseTrigger
                    allNamespaces: true
                    releaseNamePattern: "^api-.*"
                    statuses:
                      - DEPLOYED
                    on: UPDATE
                    connection:
                      masterUrl: https://prod-eu.k8s.example.com
                      caCertData: "{{ secret('PROD_EU_CA') }}"
                      oauthToken: "{{ secret('PROD_EU_TOKEN') }}"
                """
        )
    }
)
public class ReleaseTrigger extends AbstractHelmPollingTrigger implements TriggerOutput<ReleaseTrigger.Output>, StatefulTriggerInterface {
    @Schema(
        title = "Kubernetes namespace",
        description = "Namespace whose releases are watched. Ignored when `allNamespaces` is true."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<String> namespace = Property.ofValue("default");

    @Schema(
        title = "Watch every namespace",
        description = "Watches releases in all namespaces. Needs a ClusterRole that can list Secrets cluster-wide."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Boolean> allNamespaces = Property.ofValue(false);

    @Schema(
        title = "Release name",
        description = "Watches only the release with this exact name. Mutually exclusive with `releaseNamePattern`."
    )
    @PluginProperty(group = "main")
    private Property<String> releaseName;

    @Schema(
        title = "Release name pattern",
        description = "Watches only releases whose name matches this regular expression in full. Mutually exclusive with `releaseName`."
    )
    @PluginProperty(group = "main")
    private Property<String> releaseNamePattern;

    @Schema(
        title = "Statuses to fire on",
        description = "Fires only when the latest revision of a release is in one of these statuses. Every status fires when unset."
    )
    @PluginProperty(group = "main")
    private Property<List<ReleaseStatus>> statuses;

    @Schema(
        title = "Minimum time in a pending status",
        description = "When set, a release in `PENDING_INSTALL`, `PENDING_UPGRADE`, or `PENDING_ROLLBACK` fires only once it has been pending for longer than this, which tells a stuck operation apart from one still running. Other statuses are unaffected."
    )
    @PluginProperty(group = "main")
    private Property<Duration> stuckFor;

    @Schema(
        title = "Trigger event type",
        description = """
            - `CREATE`: fires for releases the trigger has not seen before.
            - `UPDATE`: fires when a release it has already seen moves to a new revision or status.
            - `CREATE_OR_UPDATE`: fires on either."""
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<On> on = Property.ofValue(On.CREATE_OR_UPDATE);

    @Schema(
        title = "State key",
        description = "KV key under which the last seen revision and status of each release is stored. Defaults to `<namespace>_<flowId>_<triggerId>`."
    )
    @PluginProperty(group = "advanced")
    private Property<String> stateKey;

    @Schema(
        title = "State TTL",
        description = "How long a release is remembered after it was last seen, e.g. `P30D`. A release that is forgotten fires again as new."
    )
    @PluginProperty(group = "advanced")
    private Property<Duration> stateTtl;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        String rNamespace = runContext.render(this.namespace).as(String.class).orElse("default");
        boolean rAllNamespaces = runContext.render(this.allNamespaces).as(Boolean.class).orElse(false);
        String rReleaseName = runContext.render(this.releaseName).as(String.class).orElse(null);
        String rReleaseNamePattern = runContext.render(this.releaseNamePattern).as(String.class).orElse(null);
        List<ReleaseStatus> rStatuses = runContext.render(this.statuses).asList(ReleaseStatus.class);
        Duration rStuckFor = runContext.render(this.stuckFor).as(Duration.class).orElse(null);
        On rOn = runContext.render(this.on).as(On.class).orElse(On.CREATE_OR_UPDATE);

        if (rReleaseName != null && rReleaseNamePattern != null) {
            throw new IllegalArgumentException("Set either `releaseName` or `releaseNamePattern`, not both.");
        }
        Pattern namePattern = rReleaseNamePattern == null ? null : Pattern.compile(rReleaseNamePattern);

        Config config = kubernetesConfig(runContext);
        String rCluster = resolveCluster(runContext, config);

        Map<String, String> selector = new LinkedHashMap<>();
        selector.put(ReleaseStorageService.OWNER_LABEL, ReleaseStorageService.OWNER);
        if (rReleaseName != null) {
            selector.put(ReleaseStorageService.NAME_LABEL, rReleaseName);
        }

        List<Secret> secrets;
        try (KubernetesClient client = ClientService.of(config)) {
            secrets = rAllNamespaces
                ? client.secrets().inAnyNamespace().withLabels(selector).list().getItems()
                : client.secrets().inNamespace(rNamespace).withLabels(selector).list().getItems();
        }

        String rStateKey = runContext.render(this.stateKey).as(String.class)
            .orElse(defaultKey(context.getNamespace(), context.getFlowId(), this.getId()));
        Optional<Duration> rStateTtl = runContext.render(this.stateTtl).as(Duration.class);
        Map<String, StatefulTriggerService.Entry> state = readState(runContext, rStateKey, rStateTtl);

        Instant now = Instant.now();
        List<ReleaseChange> changes = new ArrayList<>();

        for (Secret secret : ReleaseStorageService.latestRevisions(secrets)) {
            String name = ReleaseStorageService.releaseName(secret);
            if (namePattern != null && !namePattern.matcher(name).matches()) {
                continue;
            }

            ReleaseStatus status = ReleaseStatus.fromHelm(ReleaseStorageService.status(secret));
            Instant changedAt = ReleaseStorageService.changedAt(secret);

            // Left out of the state until stuck, so the release still counts as changed once it is.
            if (rStuckFor != null && status.isPending() && changedAt != null && changedAt.plus(rStuckFor).isAfter(now)) {
                continue;
            }

            String releaseNamespace = secret.getMetadata().getNamespace();
            Integer revision = ReleaseStorageService.revision(secret);
            String uri = rCluster + "/" + releaseNamespace + "/" + name;

            StatefulTriggerService.Entry previous = state.get(uri);
            StatefulTriggerService.Entry candidate = StatefulTriggerService.Entry.candidate(
                uri,
                revision + ":" + status.helmValue(),
                changedAt != null ? changedAt : now
            );

            boolean fire = computeAndUpdateState(state, candidate, rOn).fire();

            if (fire && (rStatuses.isEmpty() || rStatuses.contains(status))) {
                changes.add(change(runContext, secret, rCluster, releaseNamespace, name, revision, status, previous));
            }
        }

        writeState(runContext, rStateKey, state, rStateTtl);

        if (changes.isEmpty()) {
            runContext.logger().debug("No Helm release changed state");
            return Optional.empty();
        }

        runContext.logger().info("{} Helm release(s) changed state", changes.size());

        Output output = Output.builder()
            .releases(changes)
            .count(changes.size())
            .build();

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    private static ReleaseChange change(
        RunContext runContext,
        Secret secret,
        String cluster,
        String namespace,
        String name,
        Integer revision,
        ReleaseStatus status,
        StatefulTriggerService.Entry previous
    ) {
        ReleaseChange.ReleaseChangeBuilder builder = ReleaseChange.builder()
            .releaseName(name)
            .namespace(namespace)
            .cluster(cluster)
            .revision(revision)
            .status(status.helmValue())
            .previousStatus(previous == null ? null : previousStatus(previous.version()));

        Release release;
        try {
            release = ReleaseStorageService.decode(secret);
        } catch (Exception e) {
            runContext.logger().warn(
                "Could not decode release '{}' in namespace '{}'; firing without chart details: {}",
                name,
                namespace,
                e.getMessage()
            );
            return builder.build();
        }

        return builder
            .chart(release.chartName())
            .chartVersion(release.chartVersion())
            .appVersion(release.appVersion())
            .description(release.description())
            .firstDeployed(ReleaseOutput.instant(release.firstDeployed()))
            .lastDeployed(ReleaseOutput.instant(release.lastDeployed()))
            .build();
    }

    private static String previousStatus(String version) {
        int separator = version == null ? -1 : version.indexOf(':');
        return separator < 0 ? null : version.substring(separator + 1);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Releases that changed state")
        private final List<ReleaseChange> releases;

        @Schema(title = "Number of releases that changed state")
        private final Integer count;
    }

    @Builder
    @Getter
    public static class ReleaseChange {
        @Schema(title = "Release name")
        private final String releaseName;

        @Schema(title = "Namespace holding the release")
        private final String namespace;

        @Schema(
            title = "Cluster name",
            description = "The `cluster` property, or the host of the Kubernetes API server when it is unset."
        )
        private final String cluster;

        @Schema(title = "Revision of the release now current")
        private final Integer revision;

        @Schema(
            title = "Release status",
            description = "Helm status of the current revision, such as `deployed`, `failed`, or `pending-upgrade`."
        )
        private final String status;

        @Schema(
            title = "Previous status",
            description = "Status the trigger last recorded for this release. Null the first time the release is seen."
        )
        private final String previousStatus;

        @Schema(title = "Chart name")
        private final String chart;

        @Schema(title = "Chart version")
        private final String chartVersion;

        @Schema(title = "Application version")
        private final String appVersion;

        @Schema(
            title = "Revision description",
            description = "Helm's summary of the last operation, e.g. `Upgrade complete`, or the error that failed it."
        )
        private final String description;

        @Schema(title = "First deployment time")
        private final Instant firstDeployed;

        @Schema(title = "Last deployment time")
        private final Instant lastDeployed;
    }
}
