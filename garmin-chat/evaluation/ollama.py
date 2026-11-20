"""Ollama-specific model metadata and identity checks.

The evaluation runner uses this adapter only for provider-specific provenance.
It never pulls, installs or mutates a model.
"""


def model_identity(tags, name):
    matches = [
        item for item in tags.get("models", [])
        if name in {item.get("name"), item.get("model")}
    ]
    if len(matches) != 1 or not matches[0].get("digest"):
        raise ValueError(
            "Requested model must already be installed and have a digest; no auto-pull"
        )
    return {"name": name, "digest": matches[0]["digest"]}


def inspect_model(http, name):
    version = http.json("/api/version")
    model = model_identity(http.json("/api/tags"), name)
    details = http.json("/api/show", {"model": name})
    return version, model, details


def identity_unchanged(http, model):
    try:
        return model_identity(http.json("/api/tags"), model["name"]) == model
    except (OSError, ValueError, KeyError, TypeError):
        return False
