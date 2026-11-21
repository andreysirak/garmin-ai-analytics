CLUSTER := garmin-ai-analytics
CONTEXT := kind-$(CLUSTER)
KIND_CONFIG := infrastructure/kind/cluster.yaml
KIND_CONFIGURATION_CHECK := python3 infrastructure/scripts/check_kind_configuration.py
GARMIN_RAW_DATA_GID := $(shell id -g)
AIRFLOW_RAW_GROUP := --set 'scheduler.securityContexts.pod.supplementalGroups[0]=$(GARMIN_RAW_DATA_GID)'
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
CREDENTIALS_FILE ?= config/credentials.local.json
SCHEDULER_EXEC := $(KUBECTL) -n $(NAMESPACE) exec airflow-scheduler-0 -c scheduler --
JAVA_MAVEN_IMAGE := maven:3.9-eclipse-temurin-25
AIRFLOW_URL := http://localhost:8080
DAG_ID ?= garmin_daily_pipeline
DATABASE_STORAGE := python3 infrastructure/scripts/local_database_storage.py
DATABASE_CREDENTIALS := python3 infrastructure/scripts/database_credentials.py
DATABASE_STORAGE_UID := $(shell id -u)
DATABASE_STORAGE_GID := $(shell id -g)
# Colima SSHFS presents database files as the Mac owner, so PostgreSQL must use that UID.
POSTGRESQL_STORAGE_USER := --set primary.podSecurityContext.runAsUser=$(DATABASE_STORAGE_UID) \
                          --set primary.containerSecurityContext.runAsUser=$(DATABASE_STORAGE_UID)
AIRFLOW_POSTGRESQL_STORAGE_USER := --set postgresql.primary.podSecurityContext.runAsUser=$(DATABASE_STORAGE_UID) \
                                  --set postgresql.primary.containerSecurityContext.runAsUser=$(DATABASE_STORAGE_UID)
CHAT_CHART := infrastructure/helm/garmin-chat
CHAT_RELEASE := garmin-chat
CHAT_VALUES ?= $(wildcard garmin-chat/values.local.yaml)
CHAT_FRONTEND_IMAGE := garmin-chat-frontend:local
CHAT_BACKEND_IMAGE := garmin-chat-backend:local
CHAT_PORT ?= 5173

# Live evaluations are opt-in; choose a new output directory for each capture.
EVAL_OUTPUT ?=
EVAL_MODEL ?= qwen2.5:1.5b-instruct-q4_K_M
EVAL_OLLAMA_URL ?= http://127.0.0.1:11434
EVAL_APPLICATION_URL ?= http://127.0.0.1:8081
EVAL_REPETITIONS ?= 3
EVAL_STAGE ?= all
EVAL_SPLIT ?= regression
EVAL_TIMEOUT ?= 120
EVAL_TEMPERATURE ?= 0
EVAL_NUM_CTX ?= 32768
EVAL_NUM_PREDICT ?= 1024
EVAL_NUM_GPU ?= 0
EVAL_DEPLOYMENT_ID ?=
EVAL_SYNTHETIC_DATABASE ?= no
EVAL_MAVEN_FLAGS ?=
EVAL_MODULE := ai-evaluation-framework
EVAL_MAVEN = mvn -B -ntp $(EVAL_MAVEN_FLAGS) -f $(EVAL_MODULE)/pom.xml
EVAL_JAVA = $(if $(JAVA_HOME),"$(JAVA_HOME)/bin/java",java)
EVAL_CLI = $(EVAL_JAVA) -jar $(EVAL_MODULE)/target/ai-evaluation.jar

.PHONY: help prepare-garmin-raw-data cluster-create cluster-status cluster-delete infra-validate infra-up airflow-operator-rollout infra-down \
        infra-status infra-resources airflow-ui airflow-password airflow-logs test-infra java-verify \
        java-package-ingestion java-package-summary java-package-sleep \
        docker-build docker-build-summary docker-build-sleep \
        kind-load-images kind-load-ingestion-java-images \
        kind-load-ingestion-java-summary-image kind-load-ingestion-java-sleep-image \
        airflow-image kind-load-airflow-image auth-image kind-load-auth-image \
        auth-policy-verify postgresql-up postgresql-rollback postgresql-status postgresql-verify \
        postgresql-preflight postgresql-roles postgresql-password postgresql-loader-password \
        postgresql-schema-manager-password airflow-connections-import airflow-dag-reset \
        prepare-database-storage database-storage-check analytics-credentials-check airflow-credentials-check \
        stack-up stack-restart stack-config-check chat-up chat-ui chat-images kind-load-chat-images chat-validate

help:
	@echo "  make docs-check             - Check tracked Markdown links and anchors offline (Lychee 0.24.2)"
	@echo "Cluster and infrastructure:"
	@echo "  make stack-up               - Run the existing full startup sequence in order"
	@echo "  make stack-restart          - Reapply configuration and restart workloads in the existing cluster"
	@echo "  make prepare-garmin-raw-data - Prepare archive directory permissions"
	@echo "  make prepare-database-storage - Explicitly prepare new host database directories"
	@echo "  make database-storage-check - Verify host/Colima/kind mounts and database UID access"
	@echo "  make cluster-create         - Create the kind cluster"
	@echo "  make cluster-status         - Show cluster and node status"
	@echo "  make cluster-delete         - Delete the cluster; prepared host data survives (see rebuild guide)"
	@echo "  make infra-validate         - Render Kustomize and Helm manifests"
	@echo "  make infra-up               - Deploy Airflow using credentials.local.json and derived connections"
	@echo "  make infra-down             - Uninstall Airflow (keeps volumes, Secrets and cluster)"
	@echo "  make infra-status           - Show namespaces, Helm releases and Pods across the cluster"
	@echo "  make infra-resources        - Show capacity, Pod requests/limits and available live usage"
	@echo "PostgreSQL (separate from Airflow metadata):"
	@echo "  make postgresql-up          - Deploy analytics and configure role passwords from credentials.local.json"
	@echo "  make postgresql-rollback    - Restore previous release (or POSTGRESQL_REVISION=N), keeping data"
	@echo "  make postgresql-status      - Show PostgreSQL release, Pods and PVC"
	@echo "  make postgresql-verify      - Check a synthetic row across Pod replacement and rollback"
	@echo "  make postgresql-roles       - Apply version-controlled ownership and staging grants as admin"
	@echo "  make postgresql-password    - Print the analytics PostgreSQL bootstrap admin password"
	@echo "  make postgresql-loader-password - Explicit interactive rotation only; not normal setup"
	@echo "  make postgresql-schema-manager-password - Explicit interactive rotation only; not normal setup"
	@echo "  make airflow-connections-import - Resolve checked-in definitions, local overrides and private passwords"
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
	@echo "  make airflow-dag-reset      - Reset DAG metadata/history; preserve code (DAG_ID=garmin_daily_pipeline)"
	@echo "  make test-infra             - Run the KubernetesPodOperator smoke DAG"
	@echo "  make airflow-image          - Build the Airflow image with Garmin operator packages"
	@echo "  make kind-load-airflow-image - Load the local Airflow image into kind"
	@echo "Chat (existing garmin-analytics namespace):"
	@echo "  make chat-eval-test         - Standalone Java evaluation and loopback stub tests"
	@echo "  make chat-eval-component-test - Production routing, prompt, advisor and AI flow tests"
	@echo "  make chat-eval-build        - Build the standalone evaluation CLI"
	@echo "  make chat-eval-list         - List versioned AI evaluation cases without a model or database"
	@echo "  make chat-eval-model        - Evaluate installed Ollama model (requires EVAL_OUTPUT)"
	@echo "  make chat-eval-application  - Capture isolated synthetic chat path (requires explicit acknowledgement)"
	@echo "  make chat-eval-sql-test     - Run SQL checks against an explicitly supplied test database"
	@echo "  make chat-validate          - Offline chat chart lint, render checks and setup-script unit tests"
	@echo "  make chat-up                - Build/deploy the full chat stack and forward the UI"
	@echo "                                Requires the reader database Secret; CHAT_VALUES is optional"
	@echo "  make chat-ui                - Reconnect to the deployed UI on port 5173 or a free port if busy"
	@echo "                                Set CHAT_PORT to request a specific local port"

stack-config-check:
	$(DATABASE_CREDENTIALS) validate --scope analytics --file "$(CREDENTIALS_FILE)"
	AIRFLOW_CONNECTIONS_FILE="$(AIRFLOW_CONNECTIONS_FILE)" \
		$(DATABASE_CREDENTIALS) validate --scope airflow --file "$(CREDENTIALS_FILE)"

# Recursive recipes preserve dependency order even when the caller uses make -j.
stack-up: stack-config-check
	$(KIND_CONFIGURATION_CHECK) --cluster "$(CLUSTER)" --context "$(CONTEXT)" --config "$(KIND_CONFIG)" --allow-missing
	$(MAKE) prepare-database-storage
	$(MAKE) cluster-create
	$(MAKE) infra-validate
	$(MAKE) postgresql-up
	$(MAKE) infra-up

stack-restart: stack-config-check
	$(KIND_CONFIGURATION_CHECK) --cluster "$(CLUSTER)" --context "$(CONTEXT)" --config "$(KIND_CONFIG)"
	$(MAKE) stack-up
	$(KUBECTL) -n $(POSTGRESQL_NAMESPACE) rollout restart statefulset/$(POSTGRESQL_RELEASE)
	$(KUBECTL) -n $(POSTGRESQL_NAMESPACE) rollout status statefulset/$(POSTGRESQL_RELEASE) --timeout=5m
	$(KUBECTL) -n $(NAMESPACE) rollout restart statefulset/airflow-postgresql
	$(KUBECTL) -n $(NAMESPACE) rollout status statefulset/airflow-postgresql --timeout=5m
	$(KUBECTL) -n $(NAMESPACE) rollout restart deployment/airflow-api-server
	$(KUBECTL) -n $(NAMESPACE) rollout status deployment/airflow-api-server --timeout=10m
	$(KUBECTL) -n $(NAMESPACE) rollout status statefulset/airflow-scheduler --timeout=10m
	$(KUBECTL) -n $(NAMESPACE) rollout status deployment/airflow-dag-processor --timeout=10m

prepare-garmin-raw-data:
	@mkdir -p data/raw/garmin
	@find data/raw/garmin -type d -exec chgrp $(GARMIN_RAW_DATA_GID) {} + -exec chmod 2770 {} +

prepare-database-storage:
	$(DATABASE_STORAGE) prepare

database-storage-check:
	$(DATABASE_STORAGE) check --cluster "$(CLUSTER)" --context "$(CONTEXT)"

analytics-credentials-check:
	KUBE_CONTEXT="$(CONTEXT)" $(DATABASE_CREDENTIALS) check --scope analytics --file "$(CREDENTIALS_FILE)"

airflow-credentials-check:
	KUBE_CONTEXT="$(CONTEXT)" AIRFLOW_CONNECTIONS_FILE="$(AIRFLOW_CONNECTIONS_FILE)" \
		$(DATABASE_CREDENTIALS) check --scope airflow --file "$(CREDENTIALS_FILE)"

cluster-create: prepare-garmin-raw-data
	$(DATABASE_STORAGE) check-host
	@kind get clusters | grep -qx $(CLUSTER) && echo "cluster $(CLUSTER) already exists" || \
		kind create cluster --name "$(CLUSTER)" --config "$(KIND_CONFIG)"
	$(DATABASE_STORAGE) check --cluster "$(CLUSTER)" --context "$(CONTEXT)"

cluster-status:
	@kind get clusters | grep -qx $(CLUSTER) || { echo "cluster $(CLUSTER) not found"; exit 1; }
	$(KUBECTL) get nodes -o wide

cluster-delete:
	kind delete cluster --name $(CLUSTER)

infra-validate:
	kubectl kustomize infrastructure/kubernetes/overlays/local > /dev/null
	kubectl kustomize infrastructure/kubernetes/postgresql > /dev/null
	helm template $(POSTGRESQL_RELEASE) $(POSTGRESQL_CHART) --version $(POSTGRESQL_CHART_VERSION) \
		-n $(POSTGRESQL_NAMESPACE) -f $(POSTGRESQL_VALUES) \
		$(POSTGRESQL_STORAGE_USER) \
		--set-string storageIdentity=render-only > /dev/null
	helm template airflow $(AIRFLOW_CHART) -n $(NAMESPACE) \
		-f infrastructure/helm/airflow/values.yaml \
		--set-string garminRawDataGid=$(GARMIN_RAW_DATA_GID) $(AIRFLOW_RAW_GROUP) \
		$(AIRFLOW_POSTGRESQL_STORAGE_USER) \
		--set-string postgresql.storageIdentity=render-only > /dev/null
	@echo "manifests are valid"

infra-up: airflow-credentials-check database-storage-check
	# Keep image loading and permission changes behind validation, including with make -j.
	$(MAKE) prepare-garmin-raw-data kind-load-ingestion-java-images kind-load-airflow-image kind-load-auth-image
	$(KUBECTL) apply -k infrastructure/kubernetes/overlays/local
	KUBE_CONTEXT="$(CONTEXT)" CREDENTIALS_FILE="$(CREDENTIALS_FILE)" \
		AIRFLOW_CONNECTIONS_FILE="$(AIRFLOW_CONNECTIONS_FILE)" bash infrastructure/scripts/bootstrap.sh
	$(HELM) upgrade --install airflow $(AIRFLOW_CHART) \
		--namespace $(NAMESPACE) -f infrastructure/helm/airflow/values.yaml \
		--set-string garminRawDataGid=$(GARMIN_RAW_DATA_GID) $(AIRFLOW_RAW_GROUP) \
		$(AIRFLOW_POSTGRESQL_STORAGE_USER) \
		--set 'postgresql.primary.podSecurityContext.supplementalGroups[0]=$(DATABASE_STORAGE_GID)' \
		--set-string postgresql.storageIdentity="$$($(DATABASE_STORAGE) identity --database airflow)" \
		--wait --timeout 10m
	$(MAKE) airflow-operator-rollout
	$(MAKE) airflow-connections-import

airflow-operator-rollout:
	$(KUBECTL) -n $(NAMESPACE) rollout restart statefulset/airflow-scheduler deployment/airflow-dag-processor
	$(KUBECTL) -n $(NAMESPACE) rollout status statefulset/airflow-scheduler --timeout=10m
	$(KUBECTL) -n $(NAMESPACE) rollout status deployment/airflow-dag-processor --timeout=10m

infra-down:
	$(HELM) uninstall airflow --namespace $(NAMESPACE)

infra-status:
	$(KUBECTL) get namespaces
	$(HELM) list --all-namespaces
	$(KUBECTL) get pods --all-namespaces

infra-resources:
	$(KUBECTL) get nodes -o 'custom-columns=NAME:.metadata.name,CPU_CAPACITY:.status.capacity.cpu,CPU_ALLOCATABLE:.status.allocatable.cpu,MEMORY_CAPACITY:.status.capacity.memory,MEMORY_ALLOCATABLE:.status.allocatable.memory'
	@node_resources=$$($(KUBECTL) describe nodes) || exit $$?; \
		printf '%s\n' "$$node_resources" | sed -n '/^Name:/p; /^Non-terminated Pods:/,/^Events:/p'
	@if $(KUBECTL) top nodes; then \
		$(KUBECTL) top pods --all-namespaces --sort-by=memory; \
	else \
		echo "Live usage unavailable; kubectl top needs a working Metrics Server."; \
	fi

# PVC and Secret are deliberately managed outside the Helm release.
postgresql-preflight: analytics-credentials-check database-storage-check
	python3 infrastructure/scripts/check_postgresql_host_port.py --cluster "$(CLUSTER)" --context "$(CONTEXT)"

postgresql-up: postgresql-preflight
	$(KUBECTL) apply -k infrastructure/kubernetes/postgresql
	KUBE_CONTEXT="$(CONTEXT)" CREDENTIALS_FILE="$(CREDENTIALS_FILE)" \
		bash infrastructure/scripts/bootstrap.sh --postgresql-only
	$(HELM) upgrade --install $(POSTGRESQL_RELEASE) $(POSTGRESQL_CHART) \
		--version $(POSTGRESQL_CHART_VERSION) -f $(POSTGRESQL_VALUES) \
		$(POSTGRESQL_STORAGE_USER) \
		--set 'primary.podSecurityContext.supplementalGroups[0]=$(DATABASE_STORAGE_GID)' \
		--set-string storageIdentity="$$($(DATABASE_STORAGE) identity --database garmin-analytics)" \
		--namespace $(POSTGRESQL_NAMESPACE) --wait --timeout 5m
	$(MAKE) postgresql-roles
	KUBE_CONTEXT="$(CONTEXT)" python3 infrastructure/scripts/verify_postgresql_host.py

postgresql-roles:
	KUBE_CONTEXT="$(CONTEXT)" python3 infrastructure/scripts/configure_postgresql_roles.py \
		--file "$(CREDENTIALS_FILE)"

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
	KUBE_CONTEXT="$(CONTEXT)" python3 infrastructure/scripts/import_airflow_connections.py "$(AIRFLOW_CONNECTIONS_FILE)" \
		--credentials-file "$(CREDENTIALS_FILE)"

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

airflow-dag-reset:
	$(KUBECTL) -n $(NAMESPACE) exec -it deployment/airflow-api-server -c api-server -- \
		airflow dags delete "$(DAG_ID)"

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

chat-images:
	docker build -f garmin-chat/frontend/Dockerfile -t $(CHAT_FRONTEND_IMAGE) .
	docker build -f garmin-chat/backend/Dockerfile -t $(CHAT_BACKEND_IMAGE) .

kind-load-chat-images: chat-images
	kind load docker-image $(CHAT_FRONTEND_IMAGE) $(CHAT_BACKEND_IMAGE) --name $(CLUSTER)

chat-validate:
	helm lint $(CHAT_CHART) --strict --namespace $(POSTGRESQL_NAMESPACE)
	python3 -m unittest discover -s garmin-chat/tests -v

chat-up:
	@if [ -n "$(CHAT_VALUES)" ]; then test -f "$(CHAT_VALUES)" || { echo "CHAT_VALUES file not found: $(CHAT_VALUES)"; exit 1; }; fi
	helm lint $(CHAT_CHART) --strict --namespace $(POSTGRESQL_NAMESPACE) $(if $(strip $(CHAT_VALUES)),-f "$(CHAT_VALUES)")
	$(MAKE) kind-load-chat-images
	$(HELM) upgrade --install $(CHAT_RELEASE) $(CHAT_CHART) \
		--namespace $(POSTGRESQL_NAMESPACE) $(if $(strip $(CHAT_VALUES)),-f "$(CHAT_VALUES)") \
		--set-string frontend.image=$(CHAT_FRONTEND_IMAGE) --set-string backend.image=$(CHAT_BACKEND_IMAGE) \
		--set-string buildId="$$(python3 -c 'import uuid; print(uuid.uuid4())')" \
		--wait --wait-for-jobs --timeout 30m
	$(MAKE) chat-ui

chat-ui:
	@port="$(CHAT_PORT)"; \
	if [ "$(origin CHAT_PORT)" = file ] && lsof -nP -iTCP:"$$port" -sTCP:LISTEN >/dev/null 2>&1; then \
		echo "Port $$port is in use. Open the URL shown on the 'Forwarding from' line."; \
		port=; \
	else \
		echo "Open http://127.0.0.1:$$port once forwarding starts."; \
	fi; \
	echo "Keep this terminal open. Ctrl+C stops forwarding while the chat Pods stay running."; \
	exec $(KUBECTL) -n $(POSTGRESQL_NAMESPACE) port-forward --address 127.0.0.1 \
		service/$(CHAT_RELEASE)-frontend "$$port:80"

# Reusable AI evaluations. No model/DB access in offline checks.
.PHONY: chat-eval-test chat-eval-component-test chat-eval-build chat-eval-sql-test chat-eval-list chat-eval-model chat-eval-application
chat-eval-test:
	$(EVAL_MAVEN) test

chat-eval-component-test:
	mvn -B -ntp $(EVAL_MAVEN_FLAGS) -f garmin-chat/backend/pom.xml \
		-Dtest=ProductionEvaluationContractTest,GarminChatFlowTest,PromptBudgetTest,RoutingSchemaTest test

chat-eval-build:
	$(EVAL_MAVEN) -DskipTests package

chat-eval-sql-test:
	@test -n "$$GARMIN_CHAT_TEST_DB_URL" || { echo "Set an isolated PostgreSQL test URL and reader credentials first"; exit 1; }
	mvn -B -ntp $(EVAL_MAVEN_FLAGS) -f garmin-chat/backend/pom.xml -Dtest=PostgresMetricAggregationDatabaseTest test

chat-eval-list: chat-eval-build
	$(EVAL_CLI) list

chat-eval-model:
	@test -n "$(EVAL_OUTPUT)" || { echo "Set EVAL_OUTPUT=data/evaluations/NEW-RUN-NAME; existing runs are never overwritten"; exit 1; }
	$(MAKE) chat-eval-build
	$(EVAL_CLI) run --mode model \
		--ollama-url "$(EVAL_OLLAMA_URL)" --model "$(EVAL_MODEL)" \
		--stage "$(EVAL_STAGE)" --split "$(EVAL_SPLIT)" --repetitions "$(EVAL_REPETITIONS)" \
		--temperature "$(EVAL_TEMPERATURE)" --num-ctx "$(EVAL_NUM_CTX)" \
		--num-predict "$(EVAL_NUM_PREDICT)" --num-gpu "$(EVAL_NUM_GPU)" \
		--timeout "$(EVAL_TIMEOUT)" --output "$(EVAL_OUTPUT)"

chat-eval-application:
	@test -n "$(EVAL_OUTPUT)" || { echo "Set EVAL_OUTPUT=data/evaluations/NEW-RUN-NAME; existing runs are never overwritten"; exit 1; }
	@test "$(EVAL_SYNTHETIC_DATABASE)" = yes && test -n "$(EVAL_DEPLOYMENT_ID)" || { echo "Use an isolated synthetic deployment; set EVAL_SYNTHETIC_DATABASE=yes and EVAL_DEPLOYMENT_ID"; exit 1; }
	$(MAKE) chat-eval-build
	$(EVAL_CLI) run --mode application \
		--synthetic-database --deployment-id "$(EVAL_DEPLOYMENT_ID)" \
		--application-url "$(EVAL_APPLICATION_URL)" --split "$(EVAL_SPLIT)" --repetitions "$(EVAL_REPETITIONS)" \
		--timeout "$(EVAL_TIMEOUT)" --output "$(EVAL_OUTPUT)"

# Git-tracked input avoids generated/vendor documentation in local working trees.
.PHONY: docs-check
docs-check:
	git ls-files -z '*.md' | xargs -0 lychee --offline --include-fragments --no-progress
