"""Render real Helm templates with synthetic settings; never contact Kubernetes."""

import subprocess
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "infrastructure/helm/garmin-chat"


def render(*overrides, namespace="garmin-analytics"):
    return subprocess.run(
        ["helm", "template", "garmin-chat", str(CHART), "--namespace", namespace,
         *overrides], capture_output=True, text=True, check=False)


def environment(container):
    return {item["name"]: item for item in container.get("env", [])}


class ChartTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rendered = render()
        if rendered.returncode:
            raise AssertionError(rendered.stderr)
        cls.resources = [item for item in yaml.safe_load_all(rendered.stdout) if item]
        cls.deployments = {item["metadata"]["name"]: item for item in cls.resources
                           if item["kind"] == "Deployment"}

    def resource(self, kind):
        return next(item for item in self.resources if item["kind"] == kind)

    def container(self, component):
        return self.deployments[f"garmin-chat-{component}"]["spec"]["template"]["spec"]["containers"][0]

    def test_three_single_replica_deployments_and_matching_internal_services(self):
        self.assertEqual(set(self.deployments), {
            "garmin-chat-frontend", "garmin-chat-backend", "garmin-chat-ollama"})
        services = [item for item in self.resources if item["kind"] == "Service"]
        self.assertEqual(len(services), 3)
        for service in services:
            deployment = self.deployments[service["metadata"]["name"]]
            self.assertEqual(deployment["spec"]["replicas"], 1)
            labels = deployment["spec"]["template"]["metadata"]["labels"]
            self.assertTrue(service["spec"]["selector"].items() <= labels.items())
            self.assertEqual(service["spec"]["type"], "ClusterIP")
            ports = deployment["spec"]["template"]["spec"]["containers"][0]["ports"]
            self.assertIn(service["spec"]["ports"][0]["targetPort"], [p["name"] for p in ports])
        self.assertEqual(environment(self.container("frontend"))["BACKEND_HOST"]["value"],
                         "garmin-chat-backend")

    def test_backend_uses_internal_services_and_existing_secret_without_embedding_credentials(self):
        config = self.resource("ConfigMap")["data"]
        self.assertEqual(config["SERVER_ADDRESS"], "0.0.0.0")
        self.assertIn("garmin-postgresql.garmin-analytics.svc.cluster.local:5432", config["GARMIN_CHAT_DB_URL"])
        for name in ("GARMIN_CHAT_PROFILE_ID", "GARMIN_CHAT_START_DATE", "GARMIN_CHAT_END_DATE"):
            self.assertNotIn(name, config)
        self.assertEqual(config["GARMIN_CHAT_TIMEZONE"], "Europe/Oslo")
        self.assertEqual(config["GARMIN_CHAT_RESPONSE_TOKENS"], "1024")
        self.assertEqual(config["GARMIN_CHAT_MAX_QUERY_DAYS"], "31")
        self.assertEqual(config["OLLAMA_BASE_URL"], "http://garmin-chat-ollama:11434")
        backend = self.container("backend")
        for name, key in [("GARMIN_CHAT_DB_USERNAME", "username"),
                          ("GARMIN_CHAT_DB_PASSWORD", "password"),
                          ("GARMIN_CHAT_PROFILE_ID", "profile-id")]:
            self.assertEqual(environment(backend)[name]["valueFrom"]["secretKeyRef"],
                             {"name": "garmin-chat-database", "key": key})
        self.assertFalse(any(item["kind"] == "Secret" for item in self.resources))
        self.assertEqual(backend["readinessProbe"]["httpGet"]["path"], "/actuator/health/readiness")

    def test_cpu_inference_and_persistent_models_with_a_separate_initialization_job(self):
        ollama = self.container("ollama")
        self.assertRegex(ollama["image"], r"^ollama/ollama:\d+\.\d+\.\d+$")
        env = environment(ollama)
        for name in ("OLLAMA_NUM_PARALLEL", "OLLAMA_MAX_LOADED_MODELS", "OLLAMA_MAX_QUEUE"):
            self.assertEqual(env[name]["value"], "1")
        self.assertEqual(env["CUDA_VISIBLE_DEVICES"]["value"], "-1")
        self.assertNotIn("nvidia.com/gpu", ollama["resources"]["limits"])
        pvc = self.resource("PersistentVolumeClaim")
        pod = self.deployments["garmin-chat-ollama"]["spec"]
        self.assertEqual(pod["strategy"]["type"], "Recreate")
        claim = pod["template"]["spec"]["volumes"][0]["persistentVolumeClaim"]["claimName"]
        self.assertEqual(claim, pvc["metadata"]["name"])
        self.assertEqual(ollama["volumeMounts"][0]["mountPath"], env["OLLAMA_MODELS"]["value"])
        self.assertEqual(pvc["metadata"]["annotations"]["helm.sh/resource-policy"], "keep")
        job = self.resource("Job")
        self.assertNotIn("helm.sh/hook", job["metadata"].get("annotations", {}))
        pull = job["spec"]["template"]["spec"]["containers"][0]
        self.assertEqual(pull["image"], ollama["image"])
        self.assertEqual(environment(pull)["OLLAMA_HOST"]["value"],
                         self.resource("ConfigMap")["data"]["OLLAMA_BASE_URL"])
        self.assertEqual(pull["command"][2].strip(), (CHART / "files/pull-model.sh").read_text().strip())

    def test_model_context_resources_and_secret_overrides_reach_the_correct_workloads(self):
        result = render("--set-string", "ollama.model=tiny:quantized", "--set", "ollama.contextLength=4096",
                        "--set", "ollama.resources.limits.memory=4Gi",
                        "--set", "database.existingSecret=custom-reader",
                        "--set", "chat.timezone=America/New_York",
                        "--set", "chat.responseTokens=512",
                        "--set", "chat.maxQueryDays=14")
        self.assertEqual(result.returncode, 0, result.stderr)
        resources = [item for item in yaml.safe_load_all(result.stdout) if item]
        config = next(item["data"] for item in resources if item["kind"] == "ConfigMap")
        self.assertEqual(config["OLLAMA_MODEL"], "tiny:quantized")
        self.assertEqual(config["OLLAMA_CONTEXT_LENGTH"], "4096")
        self.assertEqual(config["GARMIN_CHAT_TIMEZONE"], "America/New_York")
        self.assertEqual(config["GARMIN_CHAT_RESPONSE_TOKENS"], "512")
        self.assertEqual(config["GARMIN_CHAT_MAX_QUERY_DAYS"], "14")
        for item in resources:
            if item["kind"] == "Deployment":
                container = item["spec"]["template"]["spec"]["containers"][0]
                if container["name"] == "ollama":
                    self.assertEqual(container["resources"]["limits"]["memory"], "4Gi")
                    self.assertEqual(environment(container)["OLLAMA_CONTEXT_LENGTH"]["value"], "4096")
                if container["name"] == "backend":
                    self.assertEqual(environment(container)["GARMIN_CHAT_DB_PASSWORD"]["valueFrom"]
                                     ["secretKeyRef"]["name"], "custom-reader")

    def test_wrong_namespace_and_unpinned_image_are_rejected(self):
        for kwargs, overrides in [({"namespace": "default"}, []),
                                  ({}, ["--set", "ollama.image=ollama/ollama:latest"])]:
            with self.subTest(kwargs=kwargs, overrides=overrides):
                self.assertNotEqual(render(*overrides, **kwargs).returncode, 0)

    def test_proxy_allows_both_model_calls_for_default_and_overridden_timeouts(self):
        for overrides in ([], ["--set", "chat.modelTimeoutSeconds=420"]):
            with self.subTest(overrides=overrides):
                result = render(*overrides)
                self.assertEqual(result.returncode, 0, result.stderr)
                resources = [item for item in yaml.safe_load_all(result.stdout) if item]
                config = next(item["data"] for item in resources if item["kind"] == "ConfigMap")
                frontend = next(item for item in resources if item["kind"] == "Deployment"
                                and item["metadata"]["name"] == "garmin-chat-frontend")
                env = environment(frontend["spec"]["template"]["spec"]["containers"][0])
                per_call = int(config["OLLAMA_REQUEST_TIMEOUT"].removesuffix("s"))
                proxy = int(env["API_PROXY_TIMEOUT_SECONDS"]["value"])
                self.assertGreater(proxy, 2 * per_call + 15)  # Includes the database/health checks.
                template = (ROOT / "garmin-chat/frontend/nginx.conf.template").read_text()
                self.assertIn("proxy_read_timeout ${API_PROXY_TIMEOUT_SECONDS}s;", template)
        self.assertNotEqual(render("--set", "chat.modelTimeoutSeconds=0").returncode, 0)
