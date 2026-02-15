FROM python:3.11-slim

LABEL maintainer="SENTINEL Intelligence Platform"
LABEL description="BGE-M3 sparse embedding sidecar for learned lexical retrieval"

WORKDIR /app

# Install system dependencies for torch CPU
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Install Python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application
COPY server.py .

# Non-root user for security
RUN useradd -m -s /bin/bash sidecar
USER sidecar

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD curl -f http://localhost:8091/health || exit 1

ENV PORT=8091
ENV DEVICE=cpu
ENV MODEL_NAME=BAAI/bge-m3

EXPOSE 8091

CMD ["python", "server.py"]
