# LightOnOCR Service

A FastAPI microservice providing OCR capabilities using [LightOnOCR-2-1B](https://huggingface.co/lightonai/LightOnOCR-2-1B).

Used by:
- **SENTINEL** (Java) - PDF document ingestion for RAG
- **KinCircle** (TypeScript) - Receipt and document scanning

## Quick Start

```bash
# Create virtual environment
python -m venv venv
.\venv\Scripts\activate  # Windows
# source venv/bin/activate  # Linux/Mac

# Install dependencies
pip install -r requirements.txt

# Run the service
python ocr_service.py
# Or: uvicorn ocr_service:app --host 0.0.0.0 --port 8090
```

## Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check |
| `/ocr/image` | POST | OCR base64 image |
| `/ocr/image/upload` | POST | OCR uploaded image file |
| `/ocr/pdf` | POST | OCR PDF document (all pages) |
| `/ocr/receipt` | POST | OCR receipt with structured extraction |

## API Examples

### OCR Base64 Image

```bash
curl -X POST http://localhost:8090/ocr/image \
  -H "Content-Type: application/json" \
  -d '{"image_base64": "<base64-data>", "max_tokens": 2048}'
```

### OCR PDF Upload

```bash
curl -X POST http://localhost:8090/ocr/pdf \
  -F "file=@document.pdf" \
  -F "max_tokens_per_page=2048" \
  -F "max_pages=50"
```

### OCR Receipt (Structured)

```bash
curl -X POST http://localhost:8090/ocr/receipt \
  -H "Content-Type: application/json" \
  -d '{"image_base64": "<base64-data>"}'
```

Response:
```json
{
  "raw_text": "...",
  "amount": 42.50,
  "date": "01/25/2026",
  "merchant": "CVS Pharmacy",
  "items": [],
  "processing_time_ms": 1234.5
}
```

## Hardware Requirements

| Device | Performance | Notes |
|--------|-------------|-------|
| NVIDIA GPU (8GB+) | ~5 pages/sec | Recommended |
| Apple Silicon | ~2 pages/sec | Uses MPS |
| CPU | ~0.2 pages/sec | Not recommended for production |

## Docker Deployment

```dockerfile
FROM python:3.11-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY ocr_service.py .

EXPOSE 8090
CMD ["uvicorn", "ocr_service:app", "--host", "0.0.0.0", "--port", "8090"]
```

```bash
docker build -t lightonocr-service .
docker run -p 8090:8090 --gpus all lightonocr-service
```

## Integration

### SENTINEL (Java)

Configure in `application.yaml`:
```yaml
sentinel:
  ocr:
    enabled: true
    service-url: http://localhost:8090
    timeout-seconds: 60
```

### KinCircle (TypeScript)

Set environment variable:
```bash
VITE_OCR_SERVICE_URL=http://localhost:8090
```

## Air-Gap Deployment

For SCIF/air-gapped environments:
1. Pre-download model: `huggingface-cli download lightonai/LightOnOCR-2-1B`
2. Set `HF_HUB_OFFLINE=1` environment variable
3. Point `HF_HOME` to the downloaded model directory

```bash
export HF_HUB_OFFLINE=1
export HF_HOME=/path/to/models
python ocr_service.py
```
