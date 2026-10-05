Deploy and manage Kubernetes applications from Helm charts.

This plugin wraps the `helm` CLI as orchestrated tasks, so a deployment becomes one step in a
broader workflow — build an image, run migrations, deploy the chart, smoke-test, notify — rather
than an opaque shell-out. On the Enterprise Edition each deployed release and the cluster
resources it manages are also registered as Assets, turning a fire-and-forget `helm upgrade` into
a queryable deployment record.

A `ReleaseTrigger` closes the loop the other way: it watches releases on a cluster and starts a flow
when one fails, gets stuck, or reaches a new revision, whoever made the change.

## Tasks

| Task | Purpose |
|---|---|
| `Upgrade` | `helm upgrade --install` — creates the release when absent, updates it when present |
| `Uninstall` | Removes a release and the resources it manages |
| `Rollback` | Reverts a release to an earlier revision |
| `Status` | Reads current release state without changing anything |
| `Template` | Renders a chart to manifests for review or diffing; never contacts the cluster |

## Triggers

| Trigger | Purpose |
|---|---|
| `ReleaseTrigger` | Starts a flow when a release reaches a new revision or status |

## Requirements

**Helm 4.** The default image is `alpine/helm:4.3.0`. A Helm 3 image will reject flags this plugin
relies on, including `--rollback-on-failure` and `--force-replace`, which replaced `--atomic` and
`--force` in Helm 4.

The `helm` binary runs inside a container started by the task runner (`Docker` by default), so the
Kestra worker needs access to a container runtime. Nothing needs to be installed on the worker
itself.

## Connecting to a cluster

Use `connection`, which is the same model as the Kubernetes plugin:

```yaml
- id: deploy
  type: io.kestra.plugin.helm.Upgrade
  releaseName: nginx
  namespace: web
  chart:
    repository: https://charts.bitnami.com/bitnami
    name: nginx
    version: "15.4.2"
  connection:
    masterUrl: https://prod-eu.k8s.example.com
    caCertData: "{{ secret('PROD_EU_CA') }}"
    oauthToken: "{{ secret('PROD_EU_TOKEN') }}"
```

`connection` supports an ambient service account (`inheritClusterConfig: true`), client
certificates, a bearer token, basic auth, and short-lived cloud provider tokens through
`oauthTokenProvider`. Alternatively pass a complete kubeconfig with the `kubeconfig` property, and
select a context with `kubeContext`. `connection` and `kubeconfig` are mutually exclusive.

The plugin renders whatever you supply into a kubeconfig inside the task's working directory and
points Helm at it, so credentials never appear on the command line.

## Where charts come from

A Helm repository, where the chart name and repository stay separate:

```yaml
chart:
  repository: https://charts.bitnami.com/bitnami
  name: nginx
  version: "15.4.2"
```

An OCI registry, recognised by the `oci://` prefix:

```yaml
chart:
  repository: oci://ghcr.io/stefanprodan/charts
  name: podinfo
  version: "6.15.0"
```

Or a local path, relative to the working directory — useful for a chart produced by an earlier
task:

```yaml
chart:
  path: charts/nginx
```

### Charts held in Git

There is no `git` block on `chart`. Clone the repository with the Git plugin inside a
`WorkingDirectory` and point `chart.path` at the checkout, which keeps Git authentication in one
place rather than duplicating it here:

```yaml
- id: deploy
  type: io.kestra.plugin.core.flow.WorkingDirectory
  tasks:
    - id: clone
      type: io.kestra.plugin.git.Clone
      url: https://github.com/acme/k8s-config
      branch: main
      username: "{{ secret('GH_USER') }}"
      password: "{{ secret('GH_PAT') }}"

    - id: upgrade
      type: io.kestra.plugin.helm.Upgrade
      releaseName: nginx
      namespace: web
      chart:
        path: k8s-config/charts/nginx
      valuesFrom:
        - k8s-config/prod/nginx/values.yaml
```

## Values precedence

`valuesFrom` files are applied in order, then inline `values` last. Later wins, so inline `values`
override everything:

```yaml
valuesFrom:
  - base.yaml
  - prod.yaml
values:
  image:
    tag: "{{ inputs.image_tag }}"
```

## Waiting for a deployment to be ready

Helm returns as soon as the manifests are accepted unless told otherwise, so a following task can
run against pods that are not yet serving. Set `wait: WATCHER` when that matters:

```yaml
wait: WATCHER
timeout: PT10M
rollbackOnFailure: true
```

`rollbackOnFailure` reverts the release if the upgrade fails. The task still fails, so an `errors`
block that also runs `Rollback` would move the revision a second time — pick one.

## Reviewing a change before applying it

`Template` renders locally and never contacts the cluster. `Upgrade` with `dryRun: SERVER` goes
further and validates the rendered manifests against the live API server, which catches problems
`Template` cannot. Neither emits Assets.

Both return `manifest` as a URI in Kestra's internal storage, so successive executions can be
diffed against each other.

## Assets

`Upgrade`, `Rollback`, `Status`, and `Uninstall` register the release and the resources it manages
as Assets, with the chart and values files as inputs. `Uninstall` soft-deletes them rather than
leaving them orphaned. `HelmRelease` and `KubernetesResource` aren't typed asset classes yet, so
these are `io.kestra.core.models.assets.Custom` assets, with their `type` string
(`io.kestra.plugin.ee.assets.HelmRelease` / `io.kestra.plugin.ee.assets.KubernetesResource`) chosen
to match the typed classes those would become if `core-ee` adds them later — the type string stays
identical either way, so nothing in the catalog needs to change on that swap.

**On the Enterprise Edition, add `assets: { enableAuto: true }` to the task**, or nothing is
registered:

```yaml
- id: deploy
  type: io.kestra.plugin.helm.Upgrade
  releaseName: nginx
  namespace: web
  chart:
    repository: https://charts.bitnami.com/bitnami
    name: nginx
    version: "15.4.2"
  assets:
    enableAuto: true
```

`enableAuto` is the switch that controls whether emitted assets are captured at all, not only
whether dynamically-referenced assets get auto-detected — a task without this block runs and
returns successfully, and this plugin cannot default it to `true` for you, because a non-null
`assets` block on a task fails flow validation on the open-source edition. Without it, the task
still calls the asset emission API internally, but nothing reaches the catalog.

Set `cluster`, `region`, and `environment` to label them meaningfully — `cluster` falls back to the
API server host, and the other two are not inferred. Use `resourceKinds` to narrow which kinds are
registered.

**Set `cluster` explicitly when you manage more than one cluster.** The fallback is the API server
hostname, so two clusters reached through the same load balancer or ingress hostname produce the
same Asset identity, and a release deployed to each collapses into a single catalog entry. An
explicit name per cluster keeps them distinct:

```yaml
- id: deploy_eu
  type: io.kestra.plugin.helm.Upgrade
  cluster: prod-eu
  region: europe-west1
  environment: production
  # ...

- id: deploy_us
  type: io.kestra.plugin.helm.Upgrade
  cluster: prod-us
  region: us-east1
  environment: production
  # ...
```

Assets are an Enterprise Edition feature. On the open-source edition the tasks run normally and
emission is skipped. Asset emission failures never fail an otherwise successful deploy; set
`assetFailureBehavior: FAIL` to change that.

## Reacting to release changes

`ReleaseTrigger` polls the releases on a cluster and starts a flow when one changes, whether the
change came from a Kestra task, a CI pipeline, or someone running `helm` by hand. Typical uses are
alerting on a failed deploy, rolling back a release stuck in `pending-upgrade`, and smoke-testing
every new revision:

```yaml
triggers:
  - id: failed_release
    type: io.kestra.plugin.helm.ReleaseTrigger
    namespace: web
    statuses:
      - FAILED
    cluster: prod-eu
    kubeconfig: "{{ secret('PROD_EU_KUBECONFIG') }}"
```

The matching releases are in `trigger.releases`, each with `releaseName`, `namespace`, `cluster`,
`revision`, `status`, `previousStatus`, `chart`, `chartVersion`, `appVersion`, `description`,
`firstDeployed`, and `lastDeployed`. Loop over them with `ForEach`, which hands each one to its
child tasks as a JSON string, so read fields with `fromJson(taskrun.value).releaseName`. Release
values are never included, since they often hold credentials.

**No Helm container runs.** Unlike the tasks, the trigger reads Helm's release records straight
from the Kubernetes API, so the Kestra worker needs network access to the cluster but no container
runtime. It accepts the same `connection`, `kubeconfig`, and `kubeContext` properties as the tasks.

**What fires.** Only the latest revision of each release is considered, so a revision that failed
and has since been replaced by a good one does not fire. With the default `on: CREATE_OR_UPDATE`, a
release fires once when the trigger first sees it, then again each time its revision or status
changes; `statuses` narrows which of those fire. The first poll therefore fires for every release
already on the cluster — set `on: UPDATE` to react only to changes made after the trigger starts.

**Stuck releases.** An interrupted deploy leaves a release in `pending-install`, `pending-upgrade`,
or `pending-rollback`, and Helm then refuses every later operation on it. `stuckFor` holds a pending
release back until it has been pending for that long, so a deploy that is simply still running does
not fire:

```yaml
statuses:
  - PENDING_UPGRADE
stuckFor: PT15M
```

**Permissions.** Helm stores each release revision as a Secret, so the trigger needs `list` on
`secrets`:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: kestra-helm-release-reader
  namespace: web
rules:
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["list"]
```

`allNamespaces: true` needs the same rule in a `ClusterRole`. Kubernetes RBAC cannot narrow this
permission to Helm's own Secrets, so prefer a Role scoped to the namespaces you watch.

Only releases kept with Helm's default `secret` storage driver are seen; releases stored with
`HELM_DRIVER=configmap` or `sql` are not.

## Passing a chart inline with `inputFiles`

Chart templates use Go templating, which collides with Kestra's own `{{ }}` expressions. Wrap the
content in `{% verbatim %}` … `{% endverbatim %}` or Kestra will try to evaluate it:

```yaml
inputFiles:
  hello/templates/configmap.yaml: |
    {% verbatim %}apiVersion: v1
    kind: ConfigMap
    metadata:
      name: {{ .Release.Name }}-config{% endverbatim %}
```

The tag is `verbatim`; the Jinja spelling fails with `Unexpected tag name "raw"`.

## Troubleshooting

**A failed `Upgrade` does not always mean nothing changed.** Helm applies the release before this
plugin reads the result back, so a failure during that read leaves the deploy in place. Check with
`Status` before retrying.

**The Helm container cannot reach the cluster.** It runs in its own container, so an API server
address of `127.0.0.1` refers to that container rather than your host. Use an address reachable
from inside it, and where the cluster runs in Docker, put the container on the same network with
the task runner's `networkMode`.

**`ReleaseTrigger` never fires.** Check the trigger's logs for an RBAC error: without `list` on
`secrets` in the watched namespace the poll fails rather than reporting no releases. If the
releases were installed with `HELM_DRIVER=configmap`, the trigger cannot see them.

**No Assets show up on the Enterprise Edition even though the task succeeded.** Add
`assets: { enableAuto: true }` to the task — see [Assets](#assets) above.
