curl -Lo ./kind "https://kind.sigs.k8s.io/dl/v0.31.0/kind-linux-amd64"
chmod +x ./kind
./kind create cluster --name kestra-helm --wait 120s

# The Helm CLI runs inside a container started by the Docker task runner, so the default
# kubeconfig is useless to it: that points at 127.0.0.1, which inside the container is the
# container itself. The --internal kubeconfig points at the control plane by container name,
# reachable once the Helm container joins kind's network, and that name is in the API server
# certificate so TLS verification still applies.
./kind get kubeconfig --internal --name kestra-helm > /tmp/kestra-helm-kubeconfig.yaml
