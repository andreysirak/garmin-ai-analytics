CLUSTER      := garmin-ai-analytics
CONTEXT      := kind-$(CLUSTER)
NAMESPACE    := airflow
JOBS_NAMESPACE := airflow-jobs
AIRFLOW_CHART_VERSION := 1.22.0
AIRFLOW_CHART_REPO := https://airflow.apache.org
AIRFLOW_CHART := airflow --repo $(AIRFLOW_CHART_REPO) --version $(AIRFLOW_CHART_VERSION)
KUBECTL      := kubectl --context $(CONTEXT)
HELM         := helm --kube-context $(CONTEXT)
SCHEDULER_EXEC := $(KUBECTL) -n $(NAMESPACE) exec airflow-scheduler-0 -c scheduler --
AIRFLOW_URL  := http://localhost:8080

.PHONY: help cluster-create cluster-status cluster-delete infra-validate infra-up infra-down infra-status \
        airflow-ui airflow-password airflow-logs test-infra

help:
	@echo "Cluster:"
	@echo "  make cluster-create   - Create the kind cluster (config in infrastructure/kind)"
	@echo "  make cluster-status   - Show cluster and node status"
	@echo "  make cluster-delete   - Delete the cluster (DESTROYS all in-cluster data)"
	@echo "Infrastructure:"
	@echo "  make infra-validate   - Render and validate Kustomize and Helm manifests (no cluster needed)"
	@echo "  make infra-up         - Apply Kubernetes resources, create secrets, install/upgrade Airflow"
	@echo "  make infra-down       - Uninstall Airflow (keeps volumes, secrets and cluster)"
	@echo "  make infra-status     - Show Helm release and Pods"
	@echo "Airflow:"
	@echo "  make airflow-ui       - Open the Airflow UI ($(AIRFLOW_URL))"
	@echo "  make airflow-password - Print the local 'admin' password"
	@echo "  make airflow-logs     - Tail scheduler logs"
	@echo "Tests:"
	@echo "  make test-infra       - Trigger the kubernetes_hello DAG and wait for success"

cluster-create:
	@kind get clusters | grep -qx $(CLUSTER) && echo "cluster $(CLUSTER) already exists" || \
		kind create cluster --config infrastructure/kind/cluster.yaml

cluster-status:
	@kind get clusters | grep -qx $(CLUSTER) || { echo "cluster $(CLUSTER) not found"; exit 1; }
	$(KUBECTL) get nodes -o wide

cluster-delete:
	kind delete cluster --name $(CLUSTER)

infra-validate:
	kubectl kustomize infrastructure/kubernetes/overlays/local > /dev/null
	helm template airflow $(AIRFLOW_CHART) -n $(NAMESPACE) \
		-f infrastructure/helm/airflow/values.yaml > /dev/null
	@echo "manifests are valid"

infra-up:
	$(KUBECTL) apply -k infrastructure/kubernetes/overlays/local
	KUBE_CONTEXT="$(CONTEXT)" ./infrastructure/scripts/bootstrap.sh
	$(HELM) upgrade --install airflow $(AIRFLOW_CHART) \
		--namespace $(NAMESPACE) -f infrastructure/helm/airflow/values.yaml --wait --timeout 10m

infra-down:
	$(HELM) uninstall airflow --namespace $(NAMESPACE)

infra-status:
	$(HELM) list -n $(NAMESPACE)
	$(KUBECTL) get pods -n $(NAMESPACE)
	$(KUBECTL) get pods -n $(JOBS_NAMESPACE)

airflow-ui:
	@echo "$(AIRFLOW_URL)  (user: admin, password: make airflow-password)"
	@open $(AIRFLOW_URL) 2>/dev/null || true

airflow-password:
	@$(KUBECTL) -n $(NAMESPACE) get secret airflow-simple-auth -o jsonpath='{.data.passwords\.json}' | base64 -d | python3 -c 'import json,sys; print(json.load(sys.stdin)["admin"])'

airflow-logs:
	$(KUBECTL) -n $(NAMESPACE) logs -f --tail=200 airflow-scheduler-0 -c scheduler

test-infra:
	@echo "Waiting for kubernetes_hello DAG discovery..."
	@found=0; for i in $$(seq 1 24); do \
		if $(SCHEDULER_EXEC) airflow dags list -o json 2>/dev/null | \
			python3 -c 'import json,sys; sys.exit(0 if any(d["dag_id"] == "kubernetes_hello" for d in json.load(sys.stdin)) else 1)' 2>/dev/null; then \
			found=1; break; \
		fi; \
		sleep 5; \
	done; [ "$$found" -eq 1 ] || { echo "Timed out waiting for DAG discovery"; exit 1; }
	@$(SCHEDULER_EXEC) airflow dags unpause kubernetes_hello >/dev/null
	@run_id=$$(python3 -c 'import uuid; print("smoke__" + uuid.uuid4().hex)'); \
		$(SCHEDULER_EXEC) airflow dags trigger kubernetes_hello --run-id "$$run_id" >/dev/null || exit 1; \
		echo "Triggered kubernetes_hello, waiting for result..."; \
		for i in $$(seq 1 30); do \
			s=$$($(SCHEDULER_EXEC) airflow dags list-runs kubernetes_hello -o json 2>/dev/null | python3 -c 'import json,sys; print(next((r["state"] for r in json.load(sys.stdin) if r["run_id"] == sys.argv[1]), "pending"))' "$$run_id"); \
			echo "state: $$s"; \
			[ "$$s" = success ] && exit 0; [ "$$s" = failed ] && exit 1; sleep 10; \
		done; echo "timed out"; exit 1
