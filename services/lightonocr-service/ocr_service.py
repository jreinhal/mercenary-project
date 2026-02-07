"""
LightOnOCR Microservice

A FastAPI service that provides OCR capabilities using LightOnOCR-2-1B.
Used by both SENTINEL (Java) and KinCircle (TypeScript) applications.

Usage:
    uvicorn ocr_service:app --host 0.0.0.0 --port 8090

Endpoints:
    POST /ocr/image - OCR a single image (base64 or file upload)
    POST /ocr/pdf - OCR a PDF document (returns text per page)
    GET /health - Health check
"""

import base64
import io
import logging
from typing import Optional
from contextlib import asynccontextmanager

import torch
from PIL import Image
import pypdfium2 as pdfium
from fastapi import FastAPI, File, UploadFile, HTTPException, Form
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Global model and processor (loaded once at startup)
model = None
processor = None
device = None
dtype = None


class OCRRequest(BaseModel):
    """Request body for base64 image OCR"""
    image_base64: str
    max_tokens: int = 2048


class OCRResponse(BaseModel):
    """Response from OCR endpoint"""
    text: str
    confidence: Optional[float] = None
    processing_time_ms: Optional[float] = None


class PDFOCRResponse(BaseModel):
    """Response from PDF OCR endpoint"""
    pages: list[dict]
    total_pages: int
    processing_time_ms: Optional[float] = None


class HealthResponse(BaseModel):
    """Health check response"""
    status: str
    model_loaded: bool
    device: str


def load_model():
    """Load LightOnOCR model and processor"""
    global model, processor, device, dtype

    logger.info("Loading LightOnOCR-2-1B model...")

    # Determine device and dtype
    if torch.cuda.is_available():
        device = "cuda"
        dtype = torch.bfloat16
        logger.info("Using CUDA GPU")
    elif torch.backends.mps.is_available():
        device = "mps"
        dtype = torch.float32
        logger.info("Using Apple MPS")
    else:
        device = "cpu"
        dtype = torch.float32
        logger.info("Using CPU (this will be slow)")

    try:
        from transformers import LightOnOcrForConditionalGeneration, LightOnOcrProcessor

        model = LightOnOcrForConditionalGeneration.from_pretrained(
            "lightonai/LightOnOCR-2-1B",
            torch_dtype=dtype
        ).to(device)

        processor = LightOnOcrProcessor.from_pretrained("lightonai/LightOnOCR-2-1B")

        logger.info("Model loaded successfully")
    except Exception as e:
        logger.error(f"Failed to load model: {e}")
        raise


def process_image(image: Image.Image, max_tokens: int = 2048) -> str:
    """Process a single image and return OCR text"""
    global model, processor, device, dtype

    if model is None or processor is None:
        raise RuntimeError("Model not loaded")

    # Resize image to recommended size (1540px longest dimension)
    max_dim = 1540
    ratio = max_dim / max(image.size)
    if ratio < 1:
        new_size = (int(image.size[0] * ratio), int(image.size[1] * ratio))
        image = image.resize(new_size, Image.Resampling.LANCZOS)

    # Convert to RGB if necessary
    if image.mode != "RGB":
        image = image.convert("RGB")

    # Prepare conversation format
    conversation = [{"role": "user", "content": [{"type": "image", "image": image}]}]

    # Process inputs
    inputs = processor.apply_chat_template(
        conversation,
        add_generation_prompt=True,
        tokenize=True,
        return_dict=True,
        return_tensors="pt",
    )

    # Move to device
    inputs = {
        k: v.to(device=device, dtype=dtype) if v.is_floating_point() else v.to(device)
        for k, v in inputs.items()
    }

    # Generate
    with torch.no_grad():
        output_ids = model.generate(**inputs, max_new_tokens=max_tokens)

    # Decode
    generated_ids = output_ids[0, inputs["input_ids"].shape[1]:]
    output_text = processor.decode(generated_ids, skip_special_tokens=True)

    return output_text


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load model on startup"""
    load_model()
    yield
    # Cleanup on shutdown
    global model, processor
    model = None
    processor = None
    if torch.cuda.is_available():
        torch.cuda.empty_cache()


app = FastAPI(
    title="LightOnOCR Service",
    description="OCR service using LightOnOCR-2-1B for document and receipt processing",
    version="1.0.0",
    lifespan=lifespan
)

# CORS for browser-based clients (KinCircle)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Configure appropriately for production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint"""
    return HealthResponse(
        status="healthy" if model is not None else "unhealthy",
        model_loaded=model is not None,
        device=device or "not initialized"
    )


@app.post("/ocr/image", response_model=OCRResponse)
async def ocr_image_base64(request: OCRRequest):
    """
    OCR a base64-encoded image.

    Used by: KinCircle (receipt scanning), SENTINEL (document processing)
    """
    import time
    start_time = time.time()

    try:
        # Decode base64 image
        image_data = base64.b64decode(request.image_base64)
        image = Image.open(io.BytesIO(image_data))

        # Process
        text = process_image(image, request.max_tokens)

        processing_time = (time.time() - start_time) * 1000

        return OCRResponse(
            text=text,
            processing_time_ms=processing_time
        )

    except Exception as e:
        logger.error(f"OCR failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/ocr/image/upload", response_model=OCRResponse)
async def ocr_image_upload(
    file: UploadFile = File(...),
    max_tokens: int = Form(default=2048)
):
    """
    OCR an uploaded image file.

    Used by: SENTINEL (multipart file upload)
    """
    import time
    start_time = time.time()

    try:
        # Read uploaded file
        contents = await file.read()
        image = Image.open(io.BytesIO(contents))

        # Process
        text = process_image(image, max_tokens)

        processing_time = (time.time() - start_time) * 1000

        return OCRResponse(
            text=text,
            processing_time_ms=processing_time
        )

    except Exception as e:
        logger.error(f"OCR failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/ocr/pdf", response_model=PDFOCRResponse)
async def ocr_pdf(
    file: UploadFile = File(...),
    max_tokens_per_page: int = Form(default=2048),
    max_pages: int = Form(default=50)
):
    """
    OCR a PDF document, processing each page.

    Used by: SENTINEL (PDF document ingestion)

    Returns text for each page along with page metadata.
    """
    import time
    start_time = time.time()

    try:
        # Read PDF
        contents = await file.read()
        pdf = pdfium.PdfDocument(contents)

        pages = []
        total_pages = min(len(pdf), max_pages)

        for i in range(total_pages):
            page = pdf[i]

            # Render page to image at ~200 DPI (scale=2.77)
            # Adjust scale based on page size to hit ~1540px
            page_width = page.get_width()
            page_height = page.get_height()
            max_dim = max(page_width, page_height)
            scale = 1540 / max_dim if max_dim > 0 else 2.0

            pil_image = page.render(scale=scale).to_pil()

            # OCR the page
            page_text = process_image(pil_image, max_tokens_per_page)

            pages.append({
                "page_number": i + 1,
                "text": page_text,
                "width": int(page_width),
                "height": int(page_height)
            })

            logger.info(f"Processed page {i + 1}/{total_pages}")

        processing_time = (time.time() - start_time) * 1000

        return PDFOCRResponse(
            pages=pages,
            total_pages=total_pages,
            processing_time_ms=processing_time
        )

    except Exception as e:
        logger.error(f"PDF OCR failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/ocr/receipt", response_model=dict)
async def ocr_receipt(request: OCRRequest):
    """
    OCR a receipt image and extract structured data.

    Used by: KinCircle (receipt scanning with structured output)

    Returns: { amount, date, description, merchant, items }
    """
    import time
    import re
    start_time = time.time()

    try:
        # Decode base64 image
        image_data = base64.b64decode(request.image_base64)
        image = Image.open(io.BytesIO(image_data))

        # Get raw OCR text
        raw_text = process_image(image, request.max_tokens)

        # Basic extraction patterns (can be enhanced)
        result = {
            "raw_text": raw_text,
            "amount": None,
            "date": None,
            "merchant": None,
            "items": []
        }

        # Try to extract total amount (look for TOTAL, Total, etc.)
        amount_patterns = [
            r'(?:TOTAL|Total|AMOUNT|Amount|Due|BALANCE)[:\s]*\$?([\d,]+\.?\d*)',
            r'\$\s*([\d,]+\.\d{2})\s*$'
        ]
        for pattern in amount_patterns:
            match = re.search(pattern, raw_text, re.MULTILINE)
            if match:
                result["amount"] = float(match.group(1).replace(',', ''))
                break

        # Try to extract date
        date_patterns = [
            r'(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})',
            r'(\d{4}[/-]\d{1,2}[/-]\d{1,2})'
        ]
        for pattern in date_patterns:
            match = re.search(pattern, raw_text)
            if match:
                result["date"] = match.group(1)
                break

        # First line is often the merchant
        lines = raw_text.strip().split('\n')
        if lines:
            result["merchant"] = lines[0].strip()

        processing_time = (time.time() - start_time) * 1000
        result["processing_time_ms"] = processing_time

        return result

    except Exception as e:
        logger.error(f"Receipt OCR failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8090)
