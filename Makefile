CLUSTER := garmin-ai-analytics
CONTEXT := kind-$(CLUSTER)
NAMESPACE := airflow
JOBS_NAMESPACE := airflow-jobs
AIRFLOW_CHART_VERSION := 1.22.0
AIRFLOW_CHART_REPO := https://airflow.apache.org
AIRFLOW_CHART := airflow --repo $(AIRFLOW_CHART_REPO) --version $(AIRFLOW_CHART_VERSION)
KUBECTL := kubectl --context $(CONTEXT)
HELM := helm --kube-context $(CONTEXT)
SCHEDULER_EXEC := $(KUBECTL) -n $(NAMESPACE) exec airflow-scheduler-0 -c scheduler --
JAVA_MAVEN_IMAGE := maven:3.9-eclipse-temurin-25
AIRFLOW_URL := http://localhost:8080

.PHONY: help cluster-create cluster-status cluster-delete infra-validate infra-up infra-down \
        infra-status airflow-ui airflow-password airflow-logs test-infra java-verify \
        java-package-summary java-package-sleep docker-build docker-build-summary docker-build-sleep \
        kind-load-images airflow-image kind-load-airflow-image auth-image kind-load-auth-image \
        auth-policy-verify

help:
	@echo "Cluster and infrastructure:"
	@echo "  make cluster-create         - Create the kind cluster"
	@echo "  make cluster-status         - Show cluster and node status"
	@echo "  make cluster-delete         - Delete the cluster and its in-cluster data"
	@echo "  make infra-validate         - Render Kustomize and Helm manifests"
	@echo "  make infra-up               - Apply resources, bootstrap Secrets and install Airflow"
	@echo "  make infra-down             - Uninstall Airflow (keeps volumes, Secrets and cluster)"
	@echo "  make infra-status           - Show Helm release and Pods"
	@echo "Java:"
	@echo "  make java-verify            - Run Java 25 Maven clean verify"
	@echo "  make java-package-summary  - Package daily summary and common modules"
	@echo "  make java-package-sleep    - Package sleep and common modules"
	@echo "  make docker-build           - Build both ingestion images"
	@echo "  make kind-load-images       - Load both local images into kind"
	@echo "  make auth-image             - Build the Garmin token-manager image"
	@echo "  make kind-load-auth-image   - Load the Garmin token-manager image into kind"
	@echo "  make auth-policy-verify     - Verify auth RBAC and Pod admission against the live cluster"
	@echo "Airflow:"
	@echo "  make airflow-ui             - Open the Airflow UI"
	@echo "  make airflow-password       - Print the local admin password"
	@echo "  make airflow-logs           - Tail scheduler logs"
	@echo "  make test-infra             - Run the KubernetesPodOperator smoke DAG"
	@echo "  make airflow-image          - Build the Airflow image with Garmin operator packages"
	@echo "  make kind-load-airflow-image - Load the local Airflow image into kind"

cluster-create:
	@mkdir -p data/raw/garmin
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

infra-up: kind-load-images kind-load-airflow-image kind-load-auth-image
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
	@$(KUBECTL) -n $(NAMESPACE) get secret airflow-simple-auth \
		-o jsonpath='{.data.passwords\.json}' | base64 -d | \
		python3 -c 'import json,sys; print(json.load(sys.stdin)["admin"])'

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
			s=$$($(SCHEDULER_EXEC) airflow dags list-runs kubernetes_hello -o json 2>/dev/null | \
				python3 -c 'import json,sys; print(next((r["state"] for r in json.load(sys.stdin) if r["run_id"] == sys.argv[1]), "pending"))' "$$run_id"); \
			echo "state: $$s"; \
			[ "$$s" = success ] && exit 0; [ "$$s" = failed ] && exit 1; sleep 10; \
		done; echo "timed out"; exit 1

java-verify:
	docker run --rm -v "$(PWD)/garmin-integration:/workspace" -w /workspace \
		$(JAVA_MAVEN_IMAGE) mvn -B -ntp clean verify

java-package-summary:
	docker run --rm -v "$(PWD)/garmin-integration:/workspace" -w /workspace \
		$(JAVA_MAVEN_IMAGE) mvn -B -ntp -pl garmin-daily-summary/java -am package

java-package-sleep:
	docker run --rm -v "$(PWD)/garmin-integration:/workspace" -w /workspace \
		$(JAVA_MAVEN_IMAGE) mvn -B -ntp -pl garmin-sleep/java -am package

docker-build: docker-build-summary docker-build-sleep

docker-build-summary:
	docker build -f garmin-integration/garmin-daily-summary/Dockerfile \
		-t garmin-daily-summary:local .

docker-build-sleep:
	docker build -f garmin-integration/garmin-sleep/Dockerfile \
		-t garmin-sleep:local .

kind-load-images: docker-build
	kind load docker-image garmin-daily-summary:local --name $(CLUSTER)
	kind load docker-image garmin-sleep:local --name $(CLUSTER)

airflow-image:
	docker build -f airflow/Dockerfile -t garmin-airflow:local .

kind-load-airflow-image: airflow-image
	kind load docker-image garmin-airflow:local --name $(CLUSTER)

auth-image:
	docker build -f garmin-integration/garmin-auth/Dockerfile -t garmin-auth:local .

kind-load-auth-image: auth-image
	kind load docker-image garmin-auth:local --name $(CLUSTER)

auth-policy-verify:
	KUBE_CONTEXT="$(CONTEXT)" python3 infrastructure/scripts/verify_garmin_auth_admission.py
