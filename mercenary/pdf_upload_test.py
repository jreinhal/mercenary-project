from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import letter
import io
import requests
import random
import time

def generate_pdf_content(i):
    buffer = io.BytesIO()
    c = canvas.Canvas(buffer, pagesize=letter)
    width, height = letter
    
    # 1. Random Text Content
    text_object = c.beginText(40, height - 40)
    text_object.setFont("Helvetica", 12)
    words = ["lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit", "sed", "do", "eiusmod", "tempor", "incididunt", "ut", "labore", "et", "dolore", "magna", "aliqua", "mercenary", "sentinel", "intelligence", "sector", "analysis", "hypergraph", "vector", "security"]
    
    # Variable size: random number of paragraphs
    num_paragraphs = random.randint(1, 10) 
    text_object.textLines(f"Document ID: {i}")
    text_object.textLines(" ")
    
    for _ in range(num_paragraphs):
        # Generate random paragraph
        paragraph_len = random.randint(20, 200)
        paragraph = " ".join(random.choice(words) for _ in range(paragraph_len))
        text_object.textLines(paragraph[:80]) # Simple wrapping simulation
        text_object.textLines(paragraph[80:160])
        text_object.textLines(paragraph[160:])
        text_object.textLines(" ")
        
    c.drawText(text_object)
    
    # 2. Random "Pictures" (Shapes to simulate images)
    # The user asked for "content words and pictures". Shapes are graphic elements.
    num_shapes = random.randint(1, 5)
    for _ in range(num_shapes):
        r = random.random()
        g = random.random()
        b = random.random()
        c.setFillColorRGB(r, g, b)
        
        shape_type = random.choice(['rect', 'circle'])
        x = random.randint(50, 400)
        y = random.randint(50, 500)
        
        if shape_type == 'rect':
            w = random.randint(50, 200)
            h = random.randint(50, 200)
            c.rect(x, y, w, h, fill=1, stroke=0)
        else:
            rad = random.randint(20, 80)
            c.circle(x, y, rad, fill=1, stroke=0)
            
    c.showPage()
    c.save()
    buffer.seek(0)
    return buffer

def upload_pdf(i):
    try:
        pdf_buffer = generate_pdf_content(i)
        # Randomize filename size hint or just index
        filename = f'stress_doc_{i}_{int(time.time())}.pdf'
        
        files = {'file': (filename, pdf_buffer, 'application/pdf')}
        data = {'dept': 'STRESS_TEST'}
        
        start_time = time.time()
        response = requests.post("http://localhost:8080/api/ingest/file", files=files, data=data)
        duration = time.time() - start_time
        
        return response.status_code, duration
    except Exception as e:
        print(f"Exception for {i}: {e}")
        return 0, 0

def run_test():
    total_docs = 100
    print(f"Starting upload of {total_docs} PDFs...")
    
    success_count = 0
    total_duration = 0
    
    for i in range(total_docs):
        status, duration = upload_pdf(i)
        if status == 200:
            success_count += 1
            total_duration += duration
            print(f"[{i+1}/{total_docs}] Uploaded. Time: {duration:.2f}s")
        else:
            print(f"[{i+1}/{total_docs}] FAILED. Status: {status}")
            
    print("-" * 30)
    print(f"Completed. Success: {success_count}/{total_docs}")
    if success_count > 0:
        print(f"Avg Time per Upload: {total_duration/success_count:.2f}s")

if __name__ == "__main__":
    run_test()
