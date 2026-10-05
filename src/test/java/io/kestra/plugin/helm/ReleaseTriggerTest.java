package io.kestra.plugin.helm;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.StatefulTriggerInterface.On;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.helm.models.ReleaseStatus;
import io.kestra.plugin.helm.services.KubeConfigService;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@EnableKubernetesMockClient(crud = true)
class ReleaseTriggerTest {
    static KubernetesClient client;

    @Inject
    private RunContextFactory runContextFactory;

    private String namespace() {
        return "ns-" + IdUtils.create().toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private void apply(Secret secret) {
        client.secrets().inNamespace(secret.getMetadata().getNamespace()).resource(secret).createOr(existing -> existing.update());
    }

    private ReleaseTrigger.ReleaseTriggerBuilder<?, ?> trigger(String namespace) throws Exception {
        String kubeconfig = JacksonMapper.ofYaml().writeValueAsString(
            KubeConfigService.toKubeConfig(client.getConfiguration(), namespace)
        );

        return ReleaseTrigger.builder()
            .id("release-" + IdUtils.create())
            .type(ReleaseTrigger.class.getName())
            .namespace(Property.ofValue(namespace))
            .cluster(Property.ofValue("test"))
            .kubeconfig(Property.ofValue(kubeconfig));
    }

    private Optional<Execution> evaluate(ReleaseTrigger trigger, Map.Entry<io.kestra.core.models.conditions.ConditionContext, io.kestra.core.models.triggers.Trigger> context) throws Exception {
        return trigger.evaluate(context.getKey(), context.getValue());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> releases(Execution execution) {
        return (List<Map<String, Object>>) execution.getTrigger().getVariables().get("releases");
    }

    @Test
    void shouldFireForExistingReleasesThenStayQuiet() throws Exception {
        String ns = namespace();
        apply(ReleaseSecrets.release(ns, "nginx", 1, "superseded"));
        apply(ReleaseSecrets.release(ns, "nginx", 2, "deployed"));
        apply(ReleaseSecrets.release(ns, "redis", 1, "failed"));

        ReleaseTrigger trigger = trigger(ns).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        Optional<Execution> first = evaluate(trigger, context);
        assertThat(first.isPresent(), is(true));
        assertThat(first.get().getTrigger().getVariables().get("count"), is(2));

        Map<String, Object> nginx = releases(first.get()).stream()
            .filter(release -> release.get("releaseName").equals("nginx"))
            .findFirst()
            .orElseThrow();
        assertThat(nginx.get("namespace"), is(ns));
        assertThat(nginx.get("cluster"), is("test"));
        assertThat(nginx.get("revision"), is(2));
        assertThat(nginx.get("status"), is("deployed"));
        assertThat(nginx.get("previousStatus"), is(nullValue()));
        assertThat(nginx.get("chart"), is("hello"));
        assertThat(nginx.get("chartVersion"), is("0.1.0"));
        assertThat(nginx.get("appVersion"), is("1.0.0"));
        assertThat(nginx.get("description"), is("Upgrade complete"));
        assertThat(nginx.containsKey("config"), is(false));

        assertThat(evaluate(trigger, context).isEmpty(), is(true));
    }

    @Test
    void shouldFireOnANewRevisionWithThePreviousStatus() throws Exception {
        String ns = namespace();
        apply(ReleaseSecrets.release(ns, "nginx", 1, "deployed"));

        ReleaseTrigger trigger = trigger(ns).on(Property.ofValue(On.UPDATE)).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat(evaluate(trigger, context).isEmpty(), is(true));

        apply(ReleaseSecrets.release(ns, "nginx", 1, "superseded"));
        apply(ReleaseSecrets.release(ns, "nginx", 2, "failed"));

        Optional<Execution> execution = evaluate(trigger, context);
        assertThat(execution.isPresent(), is(true));
        assertThat(releases(execution.get()), hasSize(1));
        assertThat(releases(execution.get()).getFirst().get("revision"), is(2));
        assertThat(releases(execution.get()).getFirst().get("status"), is("failed"));
        assertThat(releases(execution.get()).getFirst().get("previousStatus"), is("deployed"));
    }

    @Test
    void shouldNotFireForAFailureLeftInHistory() throws Exception {
        String ns = namespace();
        apply(ReleaseSecrets.release(ns, "nginx", 1, "superseded"));
        apply(ReleaseSecrets.release(ns, "nginx", 2, "failed"));
        apply(ReleaseSecrets.release(ns, "nginx", 3, "deployed"));

        ReleaseTrigger trigger = trigger(ns).statuses(Property.ofValue(List.of(ReleaseStatus.FAILED))).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat(evaluate(trigger, context).isEmpty(), is(true));
    }

    @Test
    void shouldFireWhenAWatchedReleaseMovesIntoAFilteredStatus() throws Exception {
        String ns = namespace();
        apply(ReleaseSecrets.release(ns, "nginx", 1, "deployed"));

        ReleaseTrigger trigger = trigger(ns).statuses(Property.ofValue(List.of(ReleaseStatus.FAILED))).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat(evaluate(trigger, context).isEmpty(), is(true));

        apply(ReleaseSecrets.release(ns, "nginx", 2, "pending-upgrade"));
        assertThat(evaluate(trigger, context).isEmpty(), is(true));

        apply(ReleaseSecrets.release(ns, "nginx", 2, "failed"));
        Optional<Execution> execution = evaluate(trigger, context);
        assertThat(execution.isPresent(), is(true));
        assertThat(releases(execution.get()).getFirst().get("previousStatus"), is("pending-upgrade"));
    }

    @Test
    void shouldWaitForStuckForBeforeFiringOnAPendingRelease() throws Exception {
        String ns = namespace();
        apply(ReleaseSecrets.release(ns, "fresh", 1, "pending-install", Instant.now(), true));
        apply(ReleaseSecrets.release(ns, "stuck", 1, "pending-install", Instant.now().minus(Duration.ofHours(1)), true));

        ReleaseTrigger trigger = trigger(ns)
            .statuses(Property.ofValue(List.of(ReleaseStatus.PENDING_INSTALL)))
            .stuckFor(Property.ofValue(Duration.ofMinutes(15)))
            .build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        Optional<Execution> execution = evaluate(trigger, context);
        assertThat(execution.isPresent(), is(true));
        assertThat(releases(execution.get()).stream().map(release -> release.get("releaseName")).toList(), containsInAnyOrder("stuck"));
    }

    @Test
    void shouldFilterByExactNameAndByPattern() throws Exception {
        String ns = namespace();
        apply(ReleaseSecrets.release(ns, "api-users", 1, "deployed"));
        apply(ReleaseSecrets.release(ns, "api-orders", 1, "deployed"));
        apply(ReleaseSecrets.release(ns, "web", 1, "deployed"));

        ReleaseTrigger exact = trigger(ns).releaseName(Property.ofValue("web")).build();
        Optional<Execution> byName = evaluate(exact, TestsUtils.mockTrigger(runContextFactory, exact));
        assertThat(releases(byName.orElseThrow()).stream().map(release -> release.get("releaseName")).toList(), containsInAnyOrder("web"));

        ReleaseTrigger pattern = trigger(ns).releaseNamePattern(Property.ofValue("api-.*")).build();
        Optional<Execution> byPattern = evaluate(pattern, TestsUtils.mockTrigger(runContextFactory, pattern));
        assertThat(
            releases(byPattern.orElseThrow()).stream().map(release -> release.get("releaseName")).toList(),
            containsInAnyOrder("api-users", "api-orders")
        );
    }

    @Test
    void shouldStillFireWhenTheReleaseBodyCannotBeDecoded() throws Exception {
        String ns = namespace();
        apply(ReleaseSecrets.withBody(ns, "broken", 1, "failed", Instant.now(), "bm90IGpzb24="));

        ReleaseTrigger trigger = trigger(ns).build();
        Optional<Execution> execution = evaluate(trigger, TestsUtils.mockTrigger(runContextFactory, trigger));

        Map<String, Object> release = releases(execution.orElseThrow()).getFirst();
        assertThat(release.get("status"), is("failed"));
        assertThat(release.get("revision"), is(1));
        assertThat(release.containsKey("chart"), is(false));
    }

    @Test
    void shouldRejectConflictingSettings() throws Exception {
        String ns = namespace();

        ReleaseTrigger names = trigger(ns)
            .releaseName(Property.ofValue("web"))
            .releaseNamePattern(Property.ofValue("web.*"))
            .build();
        assertThrows(IllegalArgumentException.class, () -> evaluate(names, TestsUtils.mockTrigger(runContextFactory, names)));

        ReleaseTrigger connections = trigger(ns)
            .connection(io.kestra.plugin.kubernetes.shared.models.Connection.builder().build())
            .build();
        assertThrows(IllegalArgumentException.class, () -> evaluate(connections, TestsUtils.mockTrigger(runContextFactory, connections)));
    }
}
