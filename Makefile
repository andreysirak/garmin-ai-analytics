COMPOSE_FILE := compose.yaml
DOCKER_COMPOSE := docker compose -f $(COMPOSE_FILE) --env-file .env

.PHONY: airflow-init airflow-up airflow-down airflow-logs airflow-ps

airflow-init:
	$(DOCKER_COMPOSE) up airflow-init

airflow-up:
	$(DOCKER_COMPOSE) up -d postgres airflow-webserver airflow-scheduler airflow-triggerer

airflow-down:
	$(DOCKER_COMPOSE) down

airflow-logs:
	$(DOCKER_COMPOSE) logs -f --tail=200 postgres airflow-webserver airflow-scheduler airflow-triggerer

airflow-ps:
	$(DOCKER_COMPOSE) ps
