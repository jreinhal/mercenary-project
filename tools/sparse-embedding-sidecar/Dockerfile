FROM python:3.11-slim

LABEL maintainer="SENTINEL Intelligence Platform"
LABEL description="BGE-M3 sparse embedding sidecar for learned lexical retrieval"

WORKDIR /app

# Install system dependencies for torch CPU
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Install Python dependencies (CPU-only torch via extra-index-url in requirements.txt)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download the BGE-M3 model at build time so the container works with read_only: true
# and in air-gapped / SCIF deployments where HuggingFace Hub is unreachable.
RUN python -c "from FlagEmbedding import BGEM3FlagModel; BGEM3FlagModel('BAAI/bge-m3', use_fp16=False)"

# Copy application
COPY server.py .

# Non-root user for security
RUN useradd -m -s /bin/bash sidecar && \
    cp -r /root/.cache /home/sidecar/.cache && \
    chown -R sidecar:sidecar /home/sidecar/.cache
USER sidecar

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD curl -f http://localhost:8091/health || exit 1

ENV PORT=8091
ENV DEVICE=cpu
ENV MODEL_NAME=BAAI/bge-m3

EXPOSE 8091

CMD ["python", "server.py"]
