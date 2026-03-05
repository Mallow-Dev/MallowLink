# MallowLink On-Device Models

This directory holds the ONNX model files used for on-device inference.
**These files are NOT bundled in version control** due to their size.

## Required model files

### 1. Embedding model (`embedding_model.onnx` + `vocab.txt`)
- **Recommended**: `all-MiniLM-L6-v2` quantised to INT8
- Output dimension: 384 floats
- Download from: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
- Export to ONNX: `optimum-cli export onnx --model all-MiniLM-L6-v2 --task feature-extraction ./onnx/`
- Quantise: `python -m onnxruntime.quantization.quantize_dynamic embedding_model.onnx embedding_model.onnx --weight_type QInt8`
- Size: ~23 MB quantised

### 2. LLM model (`llm_model.onnx` + `llm_vocab.txt`)
- **Recommended options** (choose based on device RAM):
  - `Phi-2 (2.7B)` → INT4 GPTQ → ~1.6 GB  ← best quality/speed balance
  - `Qwen2-1.5B` → INT4 AWQ → ~0.9 GB  ← lower memory requirement
  - `TinyLlama-1.1B` → INT4 → ~0.7 GB  ← most compatible
- Export via ONNX Runtime GenAI (recommended for streaming KV cache):
  ```bash
  python -m onnxruntime_genai.models.builder \
    -m microsoft/phi-2 -e cpu -p int4 -o ./onnx_phi2/
  ```
- Copy the generated `.onnx` and `vocab.json` here

## Model placement
```
assets/
  models/
    embedding_model.onnx   ← sentence-transformers model
    vocab.txt              ← WordPiece vocabulary (one token per line)
    llm_model.onnx         ← generative model
    llm_vocab.txt          ← LLM vocabulary (one token per line)
```

## Build-time download script
Run `./scripts/download_models.sh` to fetch and place pre-quantised models.
