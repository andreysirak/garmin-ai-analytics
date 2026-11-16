"""Read-only compatibility guard for the project's single-node kind configuration."""

import argparse
import json
import subprocess
import sys
from pathlib import Path

from local_database_storage import DATABASES, ROOT, STORAGE_ROOT, volume_identity


def output(*command: str) -> str:
    """Capture inspection output; never treat a failed lookup as a missing cluster."""
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout.strip()


def read_configuration(path: Path) -> dict:
    """Use kubectl's local YAML decoder without contacting the Kubernetes API."""
    configuration = json.loads(output(
        "kubectl", "patch", "--local", "--type=merge", "--patch={}",
        "--filename", str(path), "--output=json",
    ))
    if configuration.get("kind") != "Cluster" or configuration.get("apiVersion") != "kind.x-k8s.io/v1alpha4":
        raise ValueError("Expected a kind v1alpha4 Cluster configuration")
    if configuration.keys() - {"kind", "apiVersion", "name", "nodes"}:
        raise ValueError("Additional kind creation settings need explicit rebuild/preflight review")
    nodes = configuration.get("nodes", [])
    if len(nodes) != 1 or nodes[0].get("role") != "control-plane":
        raise ValueError("Node topology changed; this local stack expects one control-plane node")
    node = nodes[0]
    if node.keys() - {"role", "image", "extraMounts", "extraPortMappings"} or not node.get("image"):
        raise ValueError("Node creation settings changed or its pinned image is missing")
    return node


def expected_mounts(node: dict) -> dict:
    """Resolve kind host paths relative to the repository, as the existing Make target does."""
    mounts = {}
    for mount in node.get("extraMounts", []):
        if mount.keys() - {"hostPath", "containerPath", "readOnly"}:
            raise ValueError("Additional mount settings need explicit rebuild/preflight review")
        destination = mount["containerPath"]
        if destination in mounts or type(mount.get("readOnly", False)) is not bool:
            raise ValueError("Invalid or duplicate kind mount")
        mounts[destination] = (str((ROOT / mount["hostPath"]).resolve()), not mount.get("readOnly", False))
    return mounts


def expected_ports(node: dict) -> set:
    """Normalize fixed extra port mappings, excluding kind's implicit API-server mapping."""
    ports = set()
    for mapping in node.get("extraPortMappings", []):
        if mapping.keys() - {"containerPort", "hostPort", "listenAddress", "protocol"}:
            raise ValueError("Additional port settings need explicit rebuild/preflight review")
        if any(type(mapping.get(key)) is not int or not 1 <= mapping[key] <= 65535
               for key in ("containerPort", "hostPort")):
            raise ValueError("Extra port mappings require fixed ports between 1 and 65535")
        protocol = mapping.get("protocol", "TCP").lower()
        ports.add((f"{mapping['containerPort']}/{protocol}",
                   mapping.get("listenAddress", "0.0.0.0"), str(mapping["hostPort"])))
    return ports


def compare_node(node: dict, expected: dict) -> None:
    """Reject creation-time differences before any deployment or restart."""
    if node.get("Config", {}).get("Image") != expected["image"]:
        raise ValueError("The kind node image differs from the checked-in configuration")
    actual_mounts = {
        mount["Destination"]: (str(Path(mount["Source"]).resolve()), mount["RW"])
        for mount in node.get("Mounts", [])
        if mount["Type"] == "bind" and mount["Destination"] != "/lib/modules"
    }
    if actual_mounts != expected_mounts(expected):
        raise ValueError("The kind node bind mounts differ from the checked-in configuration")
    bindings = node.get("HostConfig", {}).get("PortBindings", {})
    actual_ports = {(port, binding["HostIp"], binding["HostPort"])
                    for port, entries in bindings.items() if port != "6443/tcp"
                    for binding in entries or []}
    if actual_ports != expected_ports(expected):
        raise ValueError("The kind node extra port mappings differ from the checked-in configuration")
    for directory, _ in expected_mounts(expected).values():
        if not Path(directory).is_dir():
            raise ValueError("An existing node's host mount directory is missing")


def check(cluster: str, path: Path, allow_missing: bool) -> bool:
    """Check compatibility and retained storage identities; return whether the cluster exists."""
    expected = read_configuration(path)
    # Validate supported mount/port shapes even before creating a fresh cluster.
    expected_mounts(expected)
    expected_ports(expected)
    exists = cluster in output("kind", "get", "clusters").splitlines()
    if not exists and not allow_missing:
        raise ValueError("The cluster is absent; use make stack-up for startup")
    if exists:
        names = output("kind", "get", "nodes", "--name", cluster).splitlines()
        if names != [f"{cluster}-control-plane"]:
            raise ValueError("The existing kind node topology differs from the configuration")
        node = json.loads(output("docker", "inspect", names[0]))[0]
        compare_node(node, expected)
    # Only a genuinely new installation may prepare missing directories automatically.
    # A partial/missing marker on an existing installation requires explicit review.
    if exists or any((STORAGE_ROOT / database).exists() for database in DATABASES):
        for database in DATABASES:
            volume_identity(STORAGE_ROOT / database, database)
    return exists


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cluster", required=True)
    parser.add_argument("--context", required=True)
    parser.add_argument("--config", type=Path, default=ROOT / "infrastructure/kind/cluster.yaml")
    parser.add_argument("--allow-missing", action="store_true")
    args = parser.parse_args()
    if args.context != f"kind-{args.cluster}":
        parser.error("CONTEXT must match kind-CLUSTER")
    try:
        exists = check(args.cluster, args.config, args.allow_missing)
    except (ValueError, OSError, KeyError, TypeError, IndexError, subprocess.CalledProcessError) as exception:
        reason = str(exception) if type(exception) is ValueError else "Cannot verify kind configuration/storage"
        sys.exit(f"{reason}. No deployment or restart was performed.\n"
                 "For creation-time changes, back up both databases and preserve the original\n"
                 "credentials.local.json/Fernet key before an explicit rebuild. Nothing was deleted.\n"
                 "See docs/database-storage.md#explicit-rebuild-after-migration-55.")
    print("kind configuration matches; cluster retained" if exists else "Cluster absent; stack-up can create it")


if __name__ == "__main__":
    main()
