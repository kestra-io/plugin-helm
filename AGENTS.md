# Kestra Helm Plugin

## What

- Provides plugin components under `io.kestra.plugin.helm`.
- Wraps the `helm` CLI as orchestrated tasks: `Upgrade`, `Uninstall`, `Rollback`, `Status`, `Template`.

## Why

- What user problem does this solve? Teams deploying Kubernetes applications from Helm charts otherwise shell out to `helm upgrade` from a `Shell` or `PodCreate` task, which gives no rollback primitive, no dry-run diff, and no record of what is running where once the execution ends.
- Why would a team adopt this plugin in a workflow? The deploy becomes one step in a broader flow — build the image, run migrations, deploy the chart, smoke-test, notify — instead of an opaque command, and on the Enterprise Edition each release and its cluster resources are registered as Assets.
- What operational/business outcome does it enable? A queryable deployment inventory, lineage from the values file in Git to the running Deployment, and a safe promotion path where `Template` or a server-side dry run renders the change for review before `Upgrade` applies it.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `helm` — the five tasks plus the `AbstractHelm` / `AbstractHelmRelease` bases
- `helm.models` — chart sourcing, release parsing, task outputs, flag enums
- `helm.services` — manifest parsing, kubeconfig rendering, Asset emission

`AbstractHelm` holds container execution (task runner, image, env, command assembly). `AbstractHelmRelease` adds the cluster connection, release identity, and Asset emission. `Template` extends `AbstractHelm` directly because it never contacts a cluster and emits no Assets.

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.helm.Upgrade` — `helm upgrade --install`
- `io.kestra.plugin.helm.Uninstall` — `helm uninstall`, soft-deletes the matching Assets
- `io.kestra.plugin.helm.Rollback` — `helm rollback`
- `io.kestra.plugin.helm.Status` — `helm status`, read-only
- `io.kestra.plugin.helm.Template` — `helm template`, no cluster contact

### Project Structure

```
plugin-helm/
├── .github/setup-unit.sh          # creates the kind cluster the integration test needs
├── src/main/java/io/kestra/plugin/helm/
│   ├── models/
│   └── services/
├── src/test/java/io/kestra/plugin/helm/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- The plugin targets **Helm 4** and **Kestra 1.3.x**. The default image is `alpine/helm:4.3.0`; a Helm 3 image rejects `--rollback-on-failure` and `--force-replace`. The dev `Dockerfile` is pinned to `kestra/kestra:v1.3.39` because `:latest` now resolves to Kestra 2.x, which fails at runtime against a 1.3.x-compiled plugin.
- Helm commands are assembled as shell strings, so every user-supplied value must go through `AbstractHelm.quote()`.
- Command strings are wrapped in `Property.ofValue`, which carries an already-resolved value, so Pebble never evaluates them — use paths relative to the container working directory rather than `{{ workingDir }}` or `{{ outputFiles[...] }}` placeholders.
- Tests that exercise a task runner must build their run context with `TestsUtils.mockRunContext`, not `runContextFactory.of()`; only the former initialises the context, and without it the Docker runner fails on a null `Optional`.
- Chart and values sourcing covers Helm repository, OCI registry, and local path. Git-sourced charts are handled by cloning with `io.kestra.plugin.git.Clone` inside a `WorkingDirectory`, not by a `git` block on the task.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
- https://helm.sh/docs/helm/helm/
