"""Read-only preflight: never recreate a kind cluster or change its resources."""

import argparse
import json
import subprocess
import sys

from postgresql_client import psql_binary


def valid_mapping(bindings: dict) -> bool:
    mappings = bindings.get("30432/tcp") or []
    return bool(mappings) and all(
        item.get("HostIp") == "127.0.0.1" and item.get("HostPort") == "5432"
        for item in mappings
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cluster", required=True)
    parser.add_argument("--context", required=True)
    args = parser.parse_args()
    if args.context != f"kind-{args.cluster}":
        parser.error("CONTEXT must match kind-CLUSTER for the host-port check")
    try:
        result = subprocess.run(
            ["docker", "inspect", f"{args.cluster}-control-plane"],
            check=True, capture_output=True, text=True,
        )
        node = json.loads(result.stdout)[0]
    except (OSError, subprocess.CalledProcessError, ValueError, IndexError):
        sys.exit("Cannot inspect the kind node. Start Docker and run make cluster-create first.")
    if not valid_mapping(node.get("HostConfig", {}).get("PortBindings", {})):
        sys.exit(
            "PostgreSQL requires kind mapping 127.0.0.1:5432 -> 30432/tcp.\n"
            "The existing cluster has NOT been changed. Docker port mappings are fixed at creation.\n"
            "Back up PostgreSQL, Airflow metadata, and Kubernetes Secrets first; see\n"
            "docs/postgresql.md#existing-clusters-back-up-before-recreation.\n"
            "Only after checking those backups, manually delete/recreate the cluster using\n"
            "infrastructure/kind/cluster.yaml, restore data/Secrets, and rerun make postgresql-up."
        )
    psql_binary()
    print("PostgreSQL loopback mapping and local psql are available")


if __name__ == "__main__":
    main()
