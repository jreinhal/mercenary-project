"""
FlagEmbedding Sparse Embedding Sidecar for SENTINEL.

Exposes BGE-M3 learned sparse (lexical) weights via a lightweight FastAPI server.
Designed to run on CPU alongside Ollama on GPU (zero additional VRAM).

Endpoints:
    GET  /health        - Model status and device info
    POST /embed-sparse  - Batch sparse embedding

Configuration via environment variables:
    MODEL_NAME  - HuggingFace model ID (default: BAAI/bge-m3)
    DEVICE      - cpu or cuda (default: cpu)
    PORT        - Server port (default: 8091)
    MAX_LENGTH  - Max token length (default: 8192)
"""

import os
import time
import logging
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
import uvicorn

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("sparse-embedding")

MODEL_NAME = os.getenv("MODEL_NAME", "BAAI/bge-m3")
DEVICE = os.getenv("DEVICE", "cpu")
MAX_LENGTH = int(os.getenv("MAX_LENGTH", "8192"))
PORT = int(os.getenv("PORT", "8091"))

model = None
model_loaded = False


def load_model():
    """Load BGE-M3 FlagEmbedding model."""
    global model, model_loaded
    logger.info("Loading model %s on %s ...", MODEL_NAME, DEVICE)
    start = time.time()
    from FlagEmbedding import BGEM3FlagModel
    use_fp16 = DEVICE != "cpu"
    model = BGEM3FlagModel(MODEL_NAME, use_fp16=use_fp16, device=DEVICE)
    model_loaded = True
    elapsed = time.time() - start
    logger.info("Model loaded in %.1fs", elapsed)


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_model()
    yield


app = FastAPI(title="SENTINEL Sparse Embedding Sidecar", lifespan=lifespan)


# ---------- Request / Response models ----------

class EmbedSparseRequest(BaseModel):
    texts: list[str] = Field(..., min_length=1, max_length=256,
                             description="Texts to embed (max 256 per batch)")


class TokenWeights(BaseModel):
    token_weights: dict[str, float]


class EmbedSparseResponse(BaseModel):
    results: list[TokenWeights]
    processing_time_ms: float


class HealthResponse(BaseModel):
    status: str
    model_loaded: bool
    model_name: str
    device: str
    max_length: int


# ---------- Endpoints ----------

@app.get("/health", response_model=HealthResponse)
def health():
    return HealthResponse(
        status="ok" if model_loaded else "loading",
        model_loaded=model_loaded,
        model_name=MODEL_NAME,
        device=DEVICE,
        max_length=MAX_LENGTH,
    )


@app.post("/embed-sparse", response_model=EmbedSparseResponse)
def embed_sparse(req: EmbedSparseRequest):
    if not model_loaded:
        raise HTTPException(status_code=503, detail="Model not loaded yet")

    start = time.time()
    output = model.encode(
        req.texts,
        max_length=MAX_LENGTH,
        return_sparse=True,
        return_dense=False,
        return_colbert_vecs=False,
    )

    # output["lexical_weights"] is a list of dicts: [{token_id: weight}, ...]
    # Convert integer token IDs to string keys for JSON serialization
    results = []
    tokenizer = model.tokenizer
    for weights_dict in output["lexical_weights"]:
        token_weights = {}
        for token_id, weight in weights_dict.items():
            token_str = tokenizer.decode([int(token_id)]).strip()
            if token_str and weight > 0.0:
                token_weights[token_str] = round(float(weight), 4)
        results.append(TokenWeights(token_weights=token_weights))

    elapsed_ms = (time.time() - start) * 1000
    logger.info("Encoded %d texts in %.1fms", len(req.texts), elapsed_ms)

    return EmbedSparseResponse(results=results, processing_time_ms=round(elapsed_ms, 1))


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=PORT, log_level="info")
