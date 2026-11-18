set -eu
attempt=0
until ollama list >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge "${MODEL_WAIT_ATTEMPTS:-60}" ]; then
    echo 'Ollama server unavailable; model initialization failed.' >&2
    exit 1
  fi
  sleep 2
done
if ollama show "$OLLAMA_MODEL" >/dev/null 2>&1; then
  echo "Model already cached: $OLLAMA_MODEL"
else
  echo "Pulling model: $OLLAMA_MODEL"
  ollama pull "$OLLAMA_MODEL"
fi
ollama show "$OLLAMA_MODEL" >/dev/null
echo "Model initialization complete: $OLLAMA_MODEL"
