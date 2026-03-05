#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# download_models.sh – Fetch quantised ONNX models for MallowLink
# Run from the project root: ./scripts/download_models.sh
# Requires: python3, pip, git-lfs
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

MODELS_DIR="app/src/main/assets/models"
mkdir -p "$MODELS_DIR"

echo "=== MallowLink Model Downloader ==="
echo

# ── 1. Embedding model (all-MiniLM-L6-v2 INT8) ───────────────────────────────
echo "Fetching embedding model…"
pip install -q "optimum[onnxruntime]" transformers

python3 - <<'EOF'
from optimum.onnxruntime import ORTModelForFeatureExtraction
from transformers import AutoTokenizer
import os, shutil

MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
OUT_DIR  = "/tmp/mallow_embedding"

print(f"  Exporting {MODEL_ID} to ONNX…")
model = ORTModelForFeatureExtraction.from_pretrained(MODEL_ID, export=True)
tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
model.save_pretrained(OUT_DIR)
tokenizer.save_pretrained(OUT_DIR)
print("  Export done.")
EOF

# Quantise to INT8
python3 - <<'EOF'
from onnxruntime.quantization import quantize_dynamic, QuantType
import os

src = "/tmp/mallow_embedding/model.onnx"
dst = "app/src/main/assets/models/embedding_model.onnx"
print(f"  Quantising {src} → {dst}…")
quantize_dynamic(src, dst, weight_type=QuantType.QInt8)
print(f"  Done. Size: {os.path.getsize(dst) / 1e6:.1f} MB")
EOF

# Copy vocab
python3 -c "
import shutil, os
src = '/tmp/mallow_embedding/vocab.txt'
if os.path.exists(src):
    shutil.copy(src, 'app/src/main/assets/models/vocab.txt')
    print('  vocab.txt copied.')
else:
    print('  WARNING: vocab.txt not found – tokenizer may use different format')
"

# ── 2. LLM (TinyLlama INT4 via ONNX Runtime GenAI) ───────────────────────────
echo
echo "Fetching LLM (TinyLlama-1.1B INT4)…"
pip install -q onnxruntime-genai 2>/dev/null || echo "  Note: onnxruntime-genai not available on this platform; skipping LLM export"

python3 - <<'EOF' 2>/dev/null || echo "  LLM export skipped – install onnxruntime-genai and re-run"
import onnxruntime_genai as og
og.Model.download(
    model_name="TinyLlama/TinyLlama-1.1B-Chat-v1.0",
    output_dir="/tmp/mallow_llm",
    precision="int4",
    execution_provider="cpu",
)
import shutil, os
out = "app/src/main/assets/models"
for f in os.listdir("/tmp/mallow_llm"):
    if f.endswith(".onnx"):
        shutil.copy(f"/tmp/mallow_llm/{f}", f"{out}/llm_model.onnx")
        print(f"  Copied LLM model: {f}")
    if "vocab" in f:
        shutil.copy(f"/tmp/mallow_llm/{f}", f"{out}/llm_vocab.txt")
        print(f"  Copied vocab: {f}")
EOF

echo
echo "=== Model download complete ==="
ls -lh "$MODELS_DIR"/*.onnx 2>/dev/null || echo "  (some models may need manual download – see assets/models/README.md)"
