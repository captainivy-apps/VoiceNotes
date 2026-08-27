#!/usr/bin/env bash
set -euo pipefail

REMOTE="root@es.galaxyrover.com"
REMOTE_DIR="/root/src.galaxyrover.com/statics/mirrors"
MIRROR_BASE="https://www.galaxyrover.com/mirrors"
HF_BASE="https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
WORKDIR="${TMPDIR:-/tmp}/asr-mirrors"

MODELS=(
  "ggml-base.bin"
  "ggml-large-v3-turbo-q5_0.bin"
)

mkdir -p "$WORKDIR"

echo "==> Downloading from HuggingFace"
for file in "${MODELS[@]}"; do
  echo "  $file"
  curl -L --fail -o "$WORKDIR/$file" "$HF_BASE/$file"
done

echo "==> Uploading to $REMOTE:$REMOTE_DIR"
ssh "$REMOTE" "mkdir -p $REMOTE_DIR"
scp "${MODELS[@]/#/$WORKDIR/}" "$REMOTE:$REMOTE_DIR/"

echo "==> Verifying mirror URLs"
for file in "${MODELS[@]}"; do
  local_size=$(stat -f%z "$WORKDIR/$file" 2>/dev/null || stat -c%s "$WORKDIR/$file")
  remote_size=$(curl -sI "$MIRROR_BASE/$file" | awk 'tolower($1)=="content-length:" {print $2}' | tr -d '\r')
  if [[ "$local_size" != "$remote_size" ]]; then
    echo "FAIL $file: local=$local_size remote=$remote_size"
    exit 1
  fi
  echo "OK   $file ($local_size bytes)"
done

echo "Done."
