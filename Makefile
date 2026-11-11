CLUSTER := garmin-ai-analytics
CONTEXT := kind-$(CLUSTER)
GARMIN_RAW_DATA_GID := $(shell id -g)
NAMESPACE := airflow
JOBS_NAMESPACE := airflow-jobs
AIRFLOW_CHART_VERSION := 1.22.0
AIRFLOW_CHART_REPO := https://airflow.apache.org
AIRFLOW_CHART := airflow --repo $(AIRFLOW_CHART_REPO) --version $(AIRFLOW_CHART_VERSION)
KUBECTL := kubectl --context $(CONTEXT)
HELM := helm --kube-context $(CONTEXT)
POSTGRESQL_NAMESPACE := garmin-analytics
POSTGRESQL_RELEASE := garmin-postgresql
POSTGRESQL_CHART := oci://registry-1.docker.io/bitnamicharts/postgresql
POSTGRESQL_CHART_VERSION := 18.12.18
POSTGRESQL_VALUES := infrastructure/helm/postgresql/values.yaml
POSTGRESQL_REVISION ?= 0
AIRFLOW_CONNECTIONS_FILE ?= config/connections.json
SCHEDULER_EXEC := $(KUBECTL) -n $(NAMESPACE) exec airflow-scheduler-0 -c scheduler --
JAVA_MAVEN_IMAGE := maven:3.9-eclipse-temurin-25
AIRFLOW_URL := http://localhost:8080

.PHONY: help prepare-garmin-raw-data cluster-create cluster-status cluster-delete infra-validate infra-up airflow-operator-rollout infra-down \
        infra-status airflow-ui airflow-password airflow-logs test-infra java-verify \
        java-package-ingestion java-package-summary java-package-sleep \
        docker-build docker-build-summary docker-build-sleep \
        kind-load-images kind-load-ingestion-java-images \
        kind-load-ingestion-java-summary-image kind-load-ingestion-java-sleep-image \
        airflow-image kind-load-airflow-image auth-image kind-load-auth-image \
        auth-policy-verify postgresql-up postgresql-rollback postgresql-status postgresql-verify \
        postgresql-preflight postgresql-roles postgresql-password postgresql-loader-password \
        postgresql-schema-manager-password airflow-connections-import

help:
	@echo "Cluster and infrastructure:"
	@echo "  make prepare-garmin-raw-data - Prepare archive directory permissions"
	@echo "  make cluster-create         - Create the kind cluster"
	@echo "  make cluster-status         - Show cluster and node status"
	@echo "  make cluster-delete         - Delete the cluster and its in-cluster data"
	@echo "  make infra-validate         - Render Kustomize and Helm manifests"
	@echo "  make infra-up               - Deploy Airflow and import the local connection file if present"
	@echo "  make infra-down             - Uninstall Airflow (keeps volumes, Secrets and cluster)"
	@echo "  make infra-status           - Show Helm release and Pods"
	@echo "PostgreSQL (separate from Airflow metadata):"
	@echo "  make postgresql-up          - Deploy garmin_analytics, preserving its PVC and Secret"
	@echo "  make postgresql-rollback    - Restore previous release (or POSTGRESQL_REVISION=N), keeping data"
	@echo "  make postgresql-status      - Show PostgreSQL release, Pods and PVC"
	@echo "  make postgresql-verify      - Check a synthetic row across Pod replacement and rollback"
	@echo "  make postgresql-roles       - Apply version-controlled ownership and staging grants as admin"
	@echo "  make postgresql-password    - Print the analytics PostgreSQL bootstrap admin password"
	@echo "  make postgresql-loader-password - Set garmin_loader password interactively (not stored in SQL)"
	@echo "  make postgresql-schema-manager-password - Set garmin_schema_manager password interactively (not stored in SQL)"
	@echo "  make airflow-connections-import - Import configured connections.json into Airflow"
	@echo "Java:"
	@echo "  make java-verify            - Run Java 25 Maven clean verify"
	@echo "  make java-package-ingestion - Package the unified ingestion JAR and common module"
	@echo "  make java-package-summary   - Alias for java-package-ingestion"
	@echo "  make java-package-sleep     - Alias for java-package-ingestion"
	@echo "  make docker-build           - Build both ingestion images"
	@echo "  make kind-load-ingestion-java-summary-image - Build and load Java summary image into kind"
	@echo "  make kind-load-ingestion-java-sleep-image   - Build and load Java sleep image into kind"
	@echo "  make kind-load-ingestion-java-images       - Build and load both Java ingestion images"
	@echo "  make kind-load-images                      - Backward-compatible alias for both Java images"
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

prepare-garmin-raw-data:
	@mkdir -p data/raw/garmin
	@find data/raw/garmin -type d -exec chgrp $(GARMIN_RAW_DATA_GID) {} + -exec chmod 2770 {} +

cluster-create: prepare-garmin-raw-data
	@kind get clusters | grep -qx $(CLUSTER) && echo "cluster $(CLUSTER) already exists" || \
		kind create cluster --config infrastructure/kind/cluster.yaml

cluster-status:
	@kind get clusters | grep -qx $(CLUSTER) || { echo "cluster $(CLUSTER) not found"; exit 1; }
	$(KUBECTL) get nodes -o wide

cluster-delete:
	kind delete cluster --name $(CLUSTER)

infra-validate:
	kubectl kustomize infrastructure/kubernetes/overlays/local > /dev/null
	kubectl kustomize infrastructure/kubernetes/postgresql > /dev/null
	helm template $(POSTGRESQL_RELEASE) $(POSTGRESQL_CHART) --version $(POSTGRESQL_CHART_VERSION) \
		-n $(POSTGRESQL_NAMESPACE) -f $(POSTGRESQL_VALUES) > /dev/null
	helm template airflow $(AIRFLOW_CHART) -n $(NAMESPACE) \
		-f infrastructure/helm/airflow/values.yaml \
		--set-string garminRawDataGid=$(GARMIN_RAW_DATA_GID) > /dev/null
	@echo "manifests are valid"

infra-up: prepare-garmin-raw-data kind-load-ingestion-java-images kind-load-airflow-image kind-load-auth-image
	$(KUBECTL) apply -k infrastructure/kubernetes/overlays/local
	KUBE_CONTEXT="$(CONTEXT)" ./infrastructure/scripts/bootstrap.sh
	$(HELM) upgrade --install airflow $(AIRFLOW_CHART) \
		--namespace $(NAMESPACE) -f infrastructure/helm/airflow/values.yaml \
		--set-string garminRawDataGid=$(GARMIN_RAW_DATA_GID) --wait --timeout 10m
	$(MAKE) airflow-operator-rollout
	KUBE_CONTEXT="$(CONTEXT)" python3 infrastructure/scripts/import_airflow_connections.py \
		--if-present "$(AIRFLOW_CONNECTIONS_FILE)"

airflow-operator-rollout:
	$(KUBECTL) -n $(NAMESPACE) rollout restart statefulset/airflow-scheduler deployment/airflow-dag-processor
	$(KUBECTL) -n $(NAMESPACE) rollout status statefulset/airflow-scheduler --timeout=10m
	$(KUBECTL) -n $(NAMESPACE) rollout status deployment/airflow-dag-processor --timeout=10m

infra-down:
	$(HELM) uninstall airflow --namespace $(NAMESPACE)

infra-status:
	$(HELM) list -n $(NAMESPACE)
	$(KUBECTL) get pods -n $(NAMESPACE)
	$(KUBECTL) get pods -n $(JOBS_NAMESPACE)

# PVC and Secret are deliberately managed outside the Helm release.
postgresql-preflight:
	python3 infrastructure/scripts/check_postgresql_host_port.py --cluster "$(CLUSTER)" --context "$(CONTEXT)"

postgresql-up: postgresql-preflight
	$(KUBECTL) apply -k infrastructure/kubernetes/postgresql
	KUBE_CONTEXT="$(CONTEXT)" ./infrastructure/scripts/bootstrap.sh --postgresql-only
	$(HELM) upgrade --install $(POSTGRESQL_RELEASE) $(POSTGRESQL_CHART) \
		--version $(POSTGRESQL_CHART_VERSION) -f $(POSTGRESQL_VALUES) \
		--namespace $(POSTGRESQL_NAMESPACE) --wait --timeout 5m
	$(MAKE) postgresql-roles
	KUBE_CONTEXT="$(CONTEXT)" python3 infrastructure/scripts/verify_postgresql_host.py

postgresql-roles:
	$(KUBECTL) -n $(POSTGRESQL_NAMESPACE) exec -i statefulset/$(POSTGRESQL_RELEASE) -c postgresql -- \
		sh -ec 'export PGPASSWORD="$$(cat "$$POSTGRES_PASSWORD_FILE")"; \
		exec psql -X -h 127.0.0.1 -U "$$POSTGRES_USER" -d "$$POSTGRES_DB" -v ON_ERROR_STOP=1' \
		< infrastructure/postgresql/roles.sql

# Read-only, independent of the host-port preflight; never generates/rotates credentials.
postgresql-password:
	@$(KUBECTL) -n $(POSTGRESQL_NAMESPACE) get secret garmin-postgresql -o json | \
		python3 -c 'import base64,json,sys; print(base64.b64decode(json.load(sys.stdin)["data"]["password"], validate=True).decode())'

postgresql-loader-password:
	$(KUBECTL) -n $(POSTGRESQL_NAMESPACE) exec -it statefulset/$(POSTGRESQL_RELEASE) -c postgresql -- \
		sh -ec 'export PGPASSWORD="$$(cat "$$POSTGRES_PASSWORD_FILE")"; \
		exec psql -X -h 127.0.0.1 -U "$$POSTGRES_USER" -d "$$POSTGRES_DB" -c "\password garmin_loader"'

postgresql-schema-manager-password:
	$(KUBECTL) -n $(POSTGRESQL_NAMESPACE) exec -it statefulset/$(POSTGRESQL_RELEASE) -c postgresql -- \
		sh -ec 'export PGPASSWORD="$$(cat "$$POSTGRES_PASSWORD_FILE")"; \
		exec psql -X -h 127.0.0.1 -U "$$POSTGRES_USER" -d "$$POSTGRES_DB" -c "\password garmin_schema_manager"'

airflow-connections-import:
	KUBE_CONTEXT="$(CONTEXT)" python3 infrastructure/scripts/import_airflow_connections.py "$(AIRFLOW_CONNECTIONS_FILE)"

postgresql-rollback:
	$(HELM) rollback $(POSTGRESQL_RELEASE) $(POSTGRESQL_REVISION) \
		--namespace $(POSTGRESQL_NAMESPACE) --wait --timeout 5m

postgresql-status:
	$(HELM) history $(POSTGRESQL_RELEASE) -n $(POSTGRESQL_NAMESPACE)
	$(KUBECTL) -n $(POSTGRESQL_NAMESPACE) get statefulset,service,pods,pvc

postgresql-verify:
	$(KUBECTL) -n $(POSTGRESQL_NAMESPACE) exec -i statefulset/$(POSTGRESQL_RELEASE) -c postgresql -- \
		sh -ec 'export PGPASSWORD="$$(cat "$$POSTGRES_PASSWORD_FILE")"; \
		exec psql -X -h 127.0.0.1 -U "$$POSTGRES_USER" -d "$$POSTGRES_DB" -v ON_ERROR_STOP=1' \
		< infrastructure/postgresql/verify_roles.sql
	KUBE_CONTEXT="$(CONTEXT)" POSTGRESQL_CHART="$(POSTGRESQL_CHART)" \
		POSTGRESQL_CHART_VERSION="$(POSTGRESQL_CHART_VERSION)" \
		python3 infrastructure/scripts/verify_postgresql_persistence.py

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

java-package-ingestion:
	docker run --rm -v "$(PWD)/garmin-integration:/workspace" -w /workspace \
		$(JAVA_MAVEN_IMAGE) mvn -B -ntp -pl garmin-data-ingestion/java -am package

java-package-summary java-package-sleep: java-package-ingestion

docker-build: docker-build-summary docker-build-sleep

docker-build-summary:
	docker build -f garmin-integration/garmin-data-ingestion/Dockerfile \
		-t garmin-daily-summary:local .

docker-build-sleep:
	docker build -f garmin-integration/garmin-data-ingestion/Dockerfile \
		-t garmin-sleep:local .

kind-load-ingestion-java-summary-image: docker-build-summary
	kind load docker-image garmin-daily-summary:local --name $(CLUSTER)

kind-load-ingestion-java-sleep-image: docker-build-sleep
	kind load docker-image garmin-sleep:local --name $(CLUSTER)

kind-load-ingestion-java-images: kind-load-ingestion-java-summary-image kind-load-ingestion-java-sleep-image

# Preserve the existing documented target for backwards compatibility.
kind-load-images: kind-load-ingestion-java-images

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
