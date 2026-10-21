# Kustomize in Kubernetes: A Complete Practical Guide to Configuration Management

*From raw YAML to reusable, environment-specific Kubernetes deployments—without turning every deployment into a template.*

Kubernetes manifests are easy to start with. A `Deployment`, `Service`, and `ConfigMap` can get an application running quickly. The challenge appears when the same application must run in development, test, staging, and production—with different replica counts, image versions, resource limits, URLs, and security settings.

Copying the same YAML into separate folders seems convenient at first. Over time, those copies drift apart. A fix reaches one environment but not another. A production patch gets accidentally copied into development. Teams need a repeatable way to share a common baseline while expressing intentional differences.

**Kustomize solves this configuration-composition problem.** It lets you keep ordinary Kubernetes YAML, define a reusable base, and layer environment-specific changes on top. It is integrated into `kubectl` through commands such as `kubectl apply -k`.

This guide explains the concepts, architecture, commands, patterns, trade-offs, operational impact, and a complete example you can adapt to your own repository.

---

## 1. What is Kustomize?

Kustomize is a Kubernetes configuration customization tool. It takes a collection of Kubernetes resource manifests and applies declared transformations—such as changing image references, adding labels, setting namespaces, generating ConfigMaps, or applying patches—to produce the final YAML to deploy.

It is **declarative**: you describe the desired configuration, rather than writing a script that edits files step by step.

It is also **template-free by design**. Unlike a templating engine that injects variables into text, Kustomize works with Kubernetes resource structures and composes resources through `kustomization.yaml` files.

Kustomize is available as part of `kubectl`:

```bash
kubectl kustomize ./overlays/dev
kubectl apply -k ./overlays/dev
```

- `kubectl kustomize` renders the final manifests to standard output.
- `kubectl apply -k` builds the Kustomize configuration and applies the resulting resources to the cluster.

You can also use the standalone `kustomize` CLI. The exact set of features available can vary by version, so check the version used by your local machine and CI/CD pipeline.

## 2. Why does Kustomize matter?

Imagine a payment API deployed to three environments.

| Setting | Development | Staging | Production |
|---|---:|---:|---:|
| Replicas | 1 | 2 | 6 |
| Container image | `registry.example.com/payments-api:1.4.0-dev` | `registry.example.com/payments-api:1.4.0-rc1` | `registry.example.com/payments-api:1.4.0` |
| Log level | DEBUG | INFO | WARN |
| Ingress hostname | `payments-dev.example.net` | `payments-staging.example.net` | `payments.example.net` |
| CPU request | 100m | 250m | 500m |

The application is the same logical service, but its deployment settings differ.

Without a composition tool, teams often maintain three nearly identical copies of the same manifests. That introduces duplication, inconsistent fixes, and a risk that one environment silently differs from another.

With Kustomize, the shared settings live in a **base**, while the differences live in **overlays**.

![Conceptual architecture: shared Kubernetes base with development, staging, and production overlays](https://placehold.co/1200x520/png?text=Shared+Base+%E2%86%92+Dev+Overlay+%7C+Staging+Overlay+%7C+Production+Overlay)

*Conceptual diagram: each overlay composes the same base with environment-specific changes. Replace this placeholder with a designed diagram before publication if your Medium editor does not render it as intended.*

### The key benefits

1. **Less duplication:** common resources are maintained once.
2. **Controlled variation:** each environment declares only its intentional differences.
3. **Reviewable changes:** a pull request can show a replica change or image update without comparing entire copied files.
4. **Reproducible rendering:** the same source configuration can be rendered in local development and CI.
5. **Kubernetes-native workflow:** the output remains ordinary Kubernetes YAML.
6. **Lower drift risk:** shared fixes can flow through a common base, provided overlays are tested and reviewed.
7. **GitOps compatibility:** controllers and pipelines can render or apply the desired configuration from version-controlled directories.

Kustomize does **not** automatically make a deployment secure, highly available, or correct. It makes configuration composition easier to structure and review; the quality of the configuration still matters.

---

## 3. Kustomize terminology

### Resource manifest

A YAML document describing a Kubernetes object, such as a `Deployment`, `Service`, `Ingress`, `ConfigMap`, or `NetworkPolicy`.

### `kustomization.yaml`

The file that tells Kustomize which resources to include and which transformations or patches to apply. The conventional filename is `kustomization.yaml` (other supported naming variants exist).

### Base

A reusable collection of manifests and a `kustomization.yaml`. A base defines the common configuration shared by multiple consumers.

### Overlay

A directory that references a base and customizes it for a particular context, such as `dev`, `staging`, or `prod`. An overlay may itself be referenced by another Kustomize configuration, enabling composition.

### Patch

A targeted modification to one or more existing resources. Patches are useful for environment-specific replicas, resource limits, probes, or other settings.

### Generator

A Kustomize feature that creates resources such as ConfigMaps or Secrets from declared inputs. Generated resource names commonly receive a content hash suffix, helping trigger workloads to pick up changed configuration when the generated name is referenced in a pod template.

### Transformer

A built-in operation that modifies selected fields across resources—for example, adding common labels, changing namespaces, or rewriting image references.

---

## 4. Kustomize versus Helm versus raw YAML

These tools solve related but different problems.

| Approach | How it works | Strong fit | Trade-off |
|---|---|---|---|
| Raw YAML only | Maintain manifests directly | Small applications with one simple deployment target | Duplication grows as environments diverge |
| Kustomize | Compose YAML resources using bases, overlays, patches, and transformers | Environment-specific variants of Kubernetes resources | Complex customization can become hard to follow if over-layered |
| Helm | Render chart templates using values and chart logic | Packaged applications, configurable distributions, release-oriented workflows | Adds template/chart concepts and rendering behavior |
| Both Helm and Kustomize | Render a chart, then customize its output in a supported workflow | Organizations needing chart packaging plus local policy/customization | More moving parts; validate the exact toolchain |

Kustomize is not universally “better than Helm.” If you need to distribute an application as a reusable chart with a large parameter surface, Helm may be a better fit. If you want to maintain normal Kubernetes YAML and apply small, explicit environment differences, Kustomize is often simpler.

A team can use both, but should define clear ownership: for example, Helm for third-party application packaging and Kustomize for the organization’s own environment overlays.

---

## 5. A practical project layout

Here is a common layout for a Java Spring Boot service named `payments-api`:

```text
payments-api-deployment/
├── base/
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── configmap.yaml
│   └── kustomization.yaml
└── overlays/
    ├── dev/
    │   ├── kustomization.yaml
    │   └── deployment-patch.yaml
    ├── staging/
    │   ├── kustomization.yaml
    │   └── deployment-patch.yaml
    └── prod/
        ├── kustomization.yaml
        └── deployment-patch.yaml
```

The base owns the shared resource definitions. Each overlay references `../../base` and declares its own differences.

**Design principle:** keep shared intent in the base; keep genuine environmental differences in overlays. Do not create an overlay for every minor variation if one common configuration is sufficient.

---

## 6. Build the base

### `base/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payments-api
  labels:
    app.kubernetes.io/name: payments-api
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: payments-api
  template:
    metadata:
      labels:
        app.kubernetes.io/name: payments-api
    spec:
      containers:
        - name: payments-api
          image: registry.example.com/payments-api:1.0.0
          ports:
            - name: http
              containerPort: 8080
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: kubernetes
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: "1"
              memory: 768Mi
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            initialDelaySeconds: 10
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: 20
            periodSeconds: 10
```

This is ordinary Kubernetes YAML. The probes assume Spring Boot Actuator health endpoints are enabled and configured. Adjust the paths to match your application.

### `base/service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: payments-api
spec:
  selector:
    app.kubernetes.io/name: payments-api
  ports:
    - name: http
      port: 80
      targetPort: http
  type: ClusterIP
```

The Service exposes the pods internally on port 80 and forwards traffic to the named container port `http` (8080).

### `base/configmap.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: payments-api-config
data:
  LOG_LEVEL: INFO
  FEATURE_NEW_CHECKOUT: "false"
```

A ConfigMap is appropriate for non-sensitive configuration. Do not store passwords, API secrets, private keys, or payment credentials in a ConfigMap. Kubernetes Secrets also need suitable encryption-at-rest, RBAC, and delivery controls; for many production environments, an external secrets manager is preferable.

This example defines the ConfigMap separately to keep the core concept visible. In a later section, we will use a Kustomize generator instead.

### `base/kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - deployment.yaml
  - service.yaml
  - configmap.yaml

commonLabels:
  app.kubernetes.io/part-of: payments-platform
```

The `resources` list includes the three manifests. `commonLabels` applies the label to resources managed by this Kustomize build. Be careful with label transformations on resources whose selectors are immutable or whose selector semantics matter; test the rendered output and understand the impact of any label changes.

---

## 7. Create the development overlay

### `overlays/dev/deployment-patch.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payments-api
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: payments-api
          image: registry.example.com/payments-api:1.4.0-dev
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
```

### `overlays/dev/kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: payments-dev

resources:
  - ../../base

patches:
  - path: deployment-patch.yaml

images:
  - name: registry.example.com/payments-api
    newTag: 1.4.0-dev

configMapGenerator:
  - name: payments-api-runtime
    literals:
      - LOG_LEVEL=DEBUG
      - FEATURE_NEW_CHECKOUT=true
```

This demonstrates several common features:

- `resources` includes the base.
- `namespace` sets the target namespace on namespaced resources.
- `patches` customizes the Deployment.
- `images` updates image references without editing the base file.
- `configMapGenerator` creates a ConfigMap from literals.

**Avoid configuring the same value in multiple places.** The sample shows both an image in the patch and an `images` transformer to illustrate the mechanisms, but a real project should choose one source of truth. For this overlay, remove the image field from `deployment-patch.yaml` and let `images` control the tag, or remove `images` and keep the image in the patch. Prefer the `images` transformer for straightforward image tag/name changes.

Also note that `payments-api-runtime` is a separate generated ConfigMap and is not yet referenced by the Deployment. To consume it, add an `envFrom` reference or explicit `configMapKeyRef` in the pod template. Do not assume creating a ConfigMap automatically wires it into the application.

For a cleaner final example, the following Deployment patch uses the image transformer and consumes the generated ConfigMap:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payments-api
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: payments-api
          envFrom:
            - configMapRef:
                name: payments-api-runtime
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
```

The generated ConfigMap name may receive a content hash suffix. Kustomize can update references to generated resources in supported reference fields, so the Deployment can reference the generated name. Validate this behavior using the rendered output.

---

## 8. Add staging and production overlays

### `overlays/staging/kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: payments-staging

resources:
  - ../../base

patches:
  - path: deployment-patch.yaml

images:
  - name: registry.example.com/payments-api
    newTag: 1.4.0-rc1

configMapGenerator:
  - name: payments-api-runtime
    literals:
      - LOG_LEVEL=INFO
      - FEATURE_NEW_CHECKOUT=true
```

### `overlays/staging/deployment-patch.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payments-api
spec:
  replicas: 2
  template:
    spec:
      containers:
        - name: payments-api
          resources:
            requests:
              cpu: 250m
              memory: 384Mi
            limits:
              cpu: "1"
              memory: 768Mi
```

### `overlays/prod/kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: payments-prod

resources:
  - ../../base

patches:
  - path: deployment-patch.yaml

images:
  - name: registry.example.com/payments-api
    newTag: 1.4.0

configMapGenerator:
  - name: payments-api-runtime
    literals:
      - LOG_LEVEL=WARN
      - FEATURE_NEW_CHECKOUT=true
```

### `overlays/prod/deployment-patch.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payments-api
spec:
  replicas: 6
  template:
    spec:
      containers:
        - name: payments-api
          resources:
            requests:
              cpu: 500m
              memory: 512Mi
            limits:
              cpu: "2"
              memory: 1Gi
```

These values are illustrative, not capacity recommendations. Production sizing should be based on load tests, latency objectives, memory profiles, autoscaling policy, node capacity, and cost constraints.

### How composition works

<escape>
            Base resources
                 |
        +--------+---------+
        |        |         |
      Dev      Staging    Prod
        |        |         |
    Dev patch  Stage patch Prod patch
    Dev image  RC image    Release image
        |        |         |
    Rendered   Rendered   Rendered
    YAML       YAML       YAML
        |        |         |
    Dev cluster Stage      Production
</escape>

The output is still Kubernetes YAML. Kustomize does not introduce a new runtime object into your cluster.

---

## 9. Render before deploying

Rendering is one of the most valuable Kustomize habits. It lets developers and reviewers inspect the exact resource definitions that will be submitted.

```bash
# Render the development configuration
kubectl kustomize overlays/dev

# Render staging
kubectl kustomize overlays/staging

# Render production
kubectl kustomize overlays/prod
```

Save output for review or debugging:

```bash
kubectl kustomize overlays/prod > rendered-prod.yaml
```

Then inspect it:

```bash
grep -nE 'namespace:|replicas:|image:|LOG_LEVEL|FEATURE_NEW_CHECKOUT' rendered-prod.yaml
```

For a real repository, use YAML-aware validation rather than relying only on text search. You should verify at least:

- expected namespace and resource names;
- approved image registry and tag or digest;
- replica and autoscaling configuration;
- CPU/memory requests and limits;
- probes and security context;
- ConfigMap/Secret references;
- ingress hostnames and TLS settings;
- network policies and service accounts;
- absence of accidental development settings in production.

### Apply to a cluster

```bash
kubectl apply -k overlays/dev
```

For production, use the reviewed, approved CI/CD or GitOps process. Do not make direct production changes merely because a local render looks correct.

Useful operational commands:

```bash
kubectl diff -k overlays/dev
kubectl apply -k overlays/dev
kubectl rollout status deployment/payments-api -n payments-dev
kubectl get deployment,service,configmap -n payments-dev
```

`kubectl diff -k` previews differences between the live cluster and the rendered configuration. It needs cluster access and may return a non-zero exit code when differences are found, so account for that behavior in automation.

---

## 10. Patches: strategic merge and JSON 6902

Patches are powerful, but should remain understandable.

### Strategic merge-style patch

A patch with Kubernetes resource structure is convenient for changing selected fields:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payments-api
spec:
  replicas: 4
```

Reference it in `kustomization.yaml`:

```yaml
patches:
  - path: replicas-patch.yaml
```

Kustomize uses the patch content and target resource to apply the change. For list fields, merge behavior depends on the Kubernetes schema and patch type; do not assume every list is merged the way you expect. Inspect the rendered result.

### JSON 6902 patch

JSON Patch is useful when the exact operation matters, such as adding or replacing a particular field.

```yaml
patches:
  - target:
      group: apps
      version: v1
      kind: Deployment
      name: payments-api
    patch: |-
      - op: replace
        path: /spec/replicas
        value: 4
```

The `replace` operation fails if the target path does not exist. Use `add` where appropriate and test against the actual base.

### Which patch style should you use?

- Use a resource-shaped patch for readable, common changes.
- Use JSON Patch for precise add/remove/replace operations.
- Keep patches small and focused.
- Avoid multiple patches that mutate the same field unless ordering and intent are very clear.
- Render and validate after every meaningful change.

---

## 11. Other useful Kustomize features

### A. Common labels and annotations

```yaml
commonLabels:
  app.kubernetes.io/part-of: payments-platform

commonAnnotations:
  owner: payments-team
  cost-center: cc-1234
```

Use labels for selection and grouping, and annotations for metadata that is not intended for selection. Depending on Kustomize version and configuration, modern alternatives such as `labels` may offer more precise control over whether labels affect selectors. Be deliberate: changing a workload selector can be disruptive or invalid because selectors are often immutable.

### B. Namespace transformation

```yaml
namespace: payments-staging
```

This sets the namespace for namespaced resources. It does not create the namespace object itself in every workflow. If the namespace must be managed from the same configuration, include a `Namespace` manifest or provision it through your platform process.

### C. Image customization

```yaml
images:
  - name: registry.example.com/payments-api
    newName: registry.prod.example.com/payments-api
    newTag: 1.4.0
```

This is often clearer than repeating image strings in many patches. In high-assurance delivery pipelines, image digests provide stronger immutability than mutable tags:

```yaml
images:
  - name: registry.example.com/payments-api
    digest: sha256:REPLACE_WITH_REAL_IMAGE_DIGEST
```

The digest above is a placeholder and must be replaced with a valid digest.

### D. ConfigMap generation from a file

```yaml
configMapGenerator:
  - name: payments-api-config
    files:
      - application.properties
```

Kustomize creates a ConfigMap based on the file. When generated content changes, the generated resource name commonly changes with a hash, and supported references are rewritten accordingly. This can cause a pod-template change and trigger a rollout.

### E. Secret generation

Kustomize can generate Kubernetes Secret resources from literals or files. However, **base64 encoding is not encryption**. Avoid committing raw credentials to Git. Prefer an external secret manager or an encrypted-secret workflow with strict access control, and ensure cluster Secret encryption and RBAC are configured.

### F. Components

Components allow reusable configuration fragments to be included by overlays—for example, an optional monitoring sidecar or a common set of observability annotations. Components are useful when a feature is shared across some, but not all, environments. Availability and syntax should be checked against the Kustomize version used by your toolchain.

### G. Replacements and other transformations

Kustomize supports mechanisms to propagate one field's value into other fields, as well as name prefixes/suffixes, replicas transformations, and other built-in transformations. Use these to avoid brittle text substitution, but do not turn a simple repository into an unnecessarily complicated transformation system.

### H. Remote bases

Kustomize can consume remote resources in supported configurations. Remote dependencies create reproducibility and supply-chain considerations. Pin sources to immutable versions or commit references where possible, review changes, and avoid depending on a moving branch for production deployments.

---

## 12. When should you use Kustomize?

Kustomize is especially useful when the same application must be deployed in multiple contexts while preserving a common baseline.

### Use case 1: Multiple environments

A shared service needs different namespaces, image versions, replica counts, resource settings, or URLs in development, test, staging, and production.

**Benefit:** common manifests stay centralized, while differences are explicit.

### Use case 2: Regional or cluster variants

A service is deployed to multiple regions or clusters with small differences in ingress hostnames, topology spread constraints, storage classes, or replica counts.

**Benefit:** a common baseline can be reused with region-specific overlays.

**Caution:** do not place secrets or sensitive infrastructure details in public or broadly accessible repository paths.

### Use case 3: Tenant-specific deployment variants

A platform deploys similar workloads for different tenants with selected differences in quotas, feature flags, or resource sizes.

**Benefit:** the common workload definition is reused.

**Caution:** for a large, dynamic tenant matrix, generating one overlay per tenant may become cumbersome. Consider whether a higher-level deployment platform or Helm chart is more suitable.

### Use case 4: GitOps

Tools such as Argo CD and Flux can work with Kustomize-based repository structures. A GitOps controller observes the desired configuration and reconciles the cluster toward it, subject to its configuration and permissions.

**Benefit:** environment configuration is versioned and reviewable, and drift can be detected or reconciled.

### Use case 5: Third-party manifest customization

You need to adjust a vendor's Kubernetes manifests—for example, add labels, change an image, or configure a resource—without maintaining a separate full copy.

**Benefit:** smaller customization layers.

**Caution:** upstream changes can break assumptions in your patches. Pin versions and validate rendered resources on upgrades.

### Use case 6: Policy and compliance workflows

A platform team can maintain a common baseline and environment-specific overlays, then validate rendered YAML against policy rules before deployment.

**Benefit:** checks can be automated in CI.

**Caution:** Kustomize itself is not a policy engine. Pair it with appropriate validation and policy tools.

---

## 13. What happens if you do not use Kustomize?

Nothing inherently breaks. Kustomize is optional. The impact depends on the complexity of your deployment model and the alternative tools you use.

### Scenario A: A single small service

If one service has one environment and a handful of stable manifests, raw YAML may be perfectly reasonable. Introducing Kustomize adds a small learning and repository-structure cost.

### Scenario B: Several environments with copied manifests

This is where the risk rises:

- a security fix may be applied to staging but missed in production;
- resource limits can drift between environments;
- teams may deploy different image versions unintentionally;
- a renamed Service or label can be changed in one copy only;
- reviews become noisy because entire files differ;
- emergency changes become harder to reproduce.

### Scenario C: A large platform with many services

Without a deliberate composition strategy, teams may create different local conventions: scripts, `sed` substitutions, duplicated directories, ad hoc CI variables, or hand-edited manifests. The organization pays in maintenance effort and operational risk.

### Important distinction

**Not using Kustomize does not automatically mean you have configuration drift.** Helm, Jsonnet, CUE, a well-designed internal generator, or carefully managed raw YAML can also provide consistent configuration. The problem is not the absence of one specific tool; it is the absence of a repeatable, tested configuration-management approach.

---

## 14. Kustomize in a production CI/CD pipeline

A mature pipeline should render and validate manifests before applying them.

<escape>
Developer change
      |
      v
Pull request + code review
      |
      v
Render Kustomize overlay
      |
      v
Schema / policy / security validation
      |
      v
Diff and review rendered resources
      |
      v
Approve environment promotion
      |
      v
Deploy through CI/CD or GitOps
      |
      v
Rollout checks + monitoring
      |
      v
Rollback or remediation if needed
</escape>

### Suggested pipeline stages

1. **Static checks:** formatting, repository conventions, and required files.
2. **Build:** compile and test the application independently of manifest rendering.
3. **Render:** run `kubectl kustomize overlays/staging`.
4. **Schema validation:** validate resources against Kubernetes schemas and the target cluster/API version.
5. **Policy checks:** enforce approved registries, non-root execution, resource requirements, and other organizational controls.
6. **Diff:** compare desired state with the target cluster or the previous reviewed output.
7. **Approval:** require the appropriate environment-specific approval.
8. **Deploy:** use a controlled CI/CD or GitOps mechanism.
9. **Verify:** check rollout status, service health, error rates, and latency.
10. **Recover:** roll back or roll forward using a defined release process.

Example rendering step:

```bash
set -euo pipefail

kubectl kustomize overlays/staging > rendered-staging.yaml
test -s rendered-staging.yaml
```

A production pipeline should pin its tool versions and make the deployment artifact traceable to a commit, image digest, and approval. Do not treat a successful render as proof that the resources will run correctly in the cluster.

---

## 15. Security and reliability considerations

Kustomize helps organize configuration; it does not replace Kubernetes security and operations practices.

### Secrets

- Do not commit plain credentials to Git.
- Base64 is encoding, not encryption.
- Use a managed secret delivery mechanism where possible.
- Restrict access through RBAC and encrypt Kubernetes Secrets at rest.
- Rotate credentials and audit access.

### Images and supply chain

- Prefer trusted registries and immutable image digests for controlled releases.
- Scan images and verify provenance/signatures where your platform supports it.
- Prevent overlays from accidentally pointing production workloads at development registries.

### Workload hardening

Consider appropriate settings for:

- `securityContext`, including non-root execution and privilege restrictions;
- resource requests and limits;
- readiness, liveness, and startup probes;
- service accounts and least-privilege RBAC;
- NetworkPolicies;
- PodDisruptionBudgets;
- topology spread or anti-affinity;
- autoscaling and graceful termination.

These settings must match the application's actual behavior. For example, a strict memory limit can cause repeated OOM kills if the JVM heap and native memory are not sized appropriately.

### Reproducibility

- Pin Kustomize/kubectl versions in CI.
- Pin remote dependencies.
- Render the exact overlay that will be deployed.
- Review rendered output, not only source patches.
- Keep production changes traceable and auditable.

---

## 16. Common mistakes and how to avoid them

| Mistake | Why it hurts | Better approach |
|---|---|---|
| Copying all manifests into each environment | Fixes and settings drift | Use a shared base and thin overlays |
| Patching the same field in several places | Intent becomes hard to predict | Keep one source of truth per field |
| Making overlays deeply nested | Review and debugging become difficult | Prefer a shallow, understandable structure |
| Using `sed` to mutate YAML | Text substitution can change unintended content | Use Kustomize transformers and patches |
| Putting credentials in ConfigMaps or Git | Secrets can leak | Use managed secret delivery and access controls |
| Assuming ConfigMap creation wires it into a pod | The app may never read the configuration | Reference the ConfigMap in the pod template |
| Assuming a successful build means a successful rollout | API acceptance and runtime behavior differ | Validate, deploy safely, and monitor |
| Updating common labels without checking selectors | Selectors may change unexpectedly or be invalid | Understand transformer behavior and inspect output |
| Using mutable tags without release controls | The same tag can resolve to different content | Prefer immutable tags or digests |
| Depending on unpinned remote bases | A future fetch can change the rendered output | Pin versions and review updates |

---

## 17. Troubleshooting checklist

### `kubectl apply -k` says no Kustomization file was found

Check that the directory contains a supported `kustomization.yaml` filename and that the command points to the overlay directory, not a parent or unrelated folder.

```bash
find . -iname 'kustomization.yaml' -o -iname 'kustomization.yml' -o -iname 'Kustomization'
kubectl kustomize overlays/dev
```

### The patch does not apply

Verify the resource `apiVersion`, `kind`, and `metadata.name`, and ensure the overlay references the patch. Render the overlay and inspect the result.

### The image did not change

The `images.name` entry must match the image reference in the resource. Check whether another patch also changes the image and remove conflicting sources of truth.

### The ConfigMap is not consumed

Ensure the pod template includes `envFrom`, `env.valueFrom.configMapKeyRef`, or a volume reference. Creating a ConfigMap alone does not make it available to the application.

### The namespace does not exist

A namespace transformer assigns the namespace to namespaced resources; it does not guarantee the namespace has been created. Provision it separately or include a Namespace resource in the appropriate configuration.

### A generated ConfigMap name changes

This is often expected: the content hash helps workloads notice configuration changes. Check that the generated name is referenced correctly and understand whether a rollout is desired.

### The rendered YAML is valid but deployment fails

Rendering is not a substitute for API-server validation, admission policy checks, permissions, capacity planning, or runtime testing. Use server-side dry run where appropriate:

```bash
kubectl apply --dry-run=server -k overlays/dev
```

This requires access to a compatible target cluster and may be affected by admission policies and API availability.

---

## 18. A hands-on lab

Try this sequence in a repository with the files from the examples above.

### Step 1: Check your tools

```bash
kubectl version --client
kubectl kustomize --help
```

### Step 2: Render the base

```bash
kubectl kustomize base
```

Observe the Deployment, Service, ConfigMap, and labels.

### Step 3: Render development

```bash
kubectl kustomize overlays/dev
```

Inspect the namespace, image tag, replicas, resource values, and generated ConfigMap.

### Step 4: Compare environments

```bash
kubectl kustomize overlays/staging > /tmp/staging.yaml
kubectl kustomize overlays/prod > /tmp/prod.yaml
diff -u /tmp/staging.yaml /tmp/prod.yaml
```

The diff should show intentional differences. If unrelated settings vary, investigate whether they should be shared.

### Step 5: Validate against a cluster

After configuring the correct context and namespace access:

```bash
kubectl apply --dry-run=server -k overlays/dev
kubectl diff -k overlays/dev
```

### Step 6: Deploy and observe

```bash
kubectl apply -k overlays/dev
kubectl rollout status deployment/payments-api -n payments-dev
kubectl get pods,svc,configmap -n payments-dev
```

If the sample app or image does not exist in your environment, the Kubernetes objects may be accepted but the pod may fail to start. Replace the image and configuration with a working application before treating this as a runtime test.

---

## 19. How to decide whether your team needs Kustomize

Ask these questions:

1. Do we deploy the same service to more than one environment?
2. Are most manifests shared, with a small number of deliberate differences?
3. Are teams copying and manually editing YAML?
4. Do environment-specific configuration changes need pull-request review?
5. Do we want CI/GitOps to render a reproducible deployment definition?
6. Can we keep overlays small enough for engineers to understand?

If most answers are yes, Kustomize is a strong candidate.

If you are packaging a complex application for broad distribution, need extensive parameterization, or already have a well-governed alternative, evaluate Helm or another configuration approach rather than adopting Kustomize by default.

---

## 20. Final takeaways

Kustomize provides a structured way to reuse Kubernetes manifests while keeping environment-specific differences explicit. Its base-and-overlay model is easy to understand, its output remains normal Kubernetes YAML, and its integration with `kubectl` makes it practical for local development, CI/CD, and GitOps workflows.

The real value is not simply fewer YAML lines. It is **making deployment intent visible, reviewable, testable, and repeatable**.

Use Kustomize when you have a shared Kubernetes baseline and manageable variations. Keep patches focused, avoid conflicting sources of truth, validate rendered manifests, handle secrets correctly, pin dependencies, and verify real cluster behavior. If you do not use Kustomize, adopt another deliberate approach to prevent configuration drift.

### Quick reference

```bash
# Render a Kustomize directory
kubectl kustomize overlays/dev

# Apply the rendered configuration
kubectl apply -k overlays/dev

# Preview differences against the cluster
kubectl diff -k overlays/dev

# Validate using the API server without persisting changes
kubectl apply --dry-run=server -k overlays/dev

# Check rollout
kubectl rollout status deployment/payments-api -n payments-dev
```

---

## Further reading

- Kubernetes documentation — Kustomize: https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/
- Kustomize project and documentation: https://kubectl.docs.kubernetes.io/
- Kubernetes Deployments: https://kubernetes.io/docs/concepts/workloads/controllers/deployment/
- Kubernetes ConfigMaps: https://kubernetes.io/docs/concepts/configuration/configmap/
- Kubernetes Secrets: https://kubernetes.io/docs/concepts/configuration/secret/
- Kubernetes security checklist: https://kubernetes.io/docs/concepts/security/

*Editorial note for Medium:* the architecture illustrations in this Markdown use a textual flow and a placeholder image URL. If your publishing workflow supports Mermaid, convert the flows to Mermaid diagrams; otherwise replace the placeholder with a custom architecture graphic before publication.
