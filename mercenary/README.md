# Mercenary AI - Air-Gapped Defense Intelligence System

Mercenary AI is a secure, local Retrieval Augmented Generation (RAG) application designed for defense-grade intelligence analysis. It allows users to ingest sensitive documents into a secure "Vault" and perform interrogation-style Q&A using a local Large Language Model (LLM), ensuring no data leaves the secure environment (excluding the encrypted vector store).

## 🛠 Technology Stack
* **Core:** Java 21, Spring Boot 3.3
* **AI Framework:** Spring AI (1.0.0-M1)
* **Local Brain:** Ollama (Llama 3 for generation, Nomic-Embed-Text for vectorization)
* **Vector Database:** MongoDB Atlas (Vector Search)
* **Documentation:** OpenAPI / Swagger UI

---

## 📋 Prerequisites

Before running the application, ensure the following are installed:
1.  **Java 21 SDK**
2.  **Ollama** (Running locally on port 11434)
3.  **MongoDB Atlas Account** (Free tier is sufficient)

---

## 🚀 Installation & Setup

### 1. Initialize the Local AI Brain (Ollama)
This application runs "Air-Gapped" from the internet for inference. You must download the models to your local machine.

1.  Install [Ollama](https://ollama.com/).
2.  Open a terminal/PowerShell and run the following commands to download the required models:
    ```bash
    ollama pull llama3
    ollama pull nomic-embed-text
    ```
3.  Start the Ollama server:
    ```bash
    ollama serve
    ```
    *(Keep this window open while the application runs)*.

### 2. Configure the Vector Database (Critical)
The application is configured with `initialize-schema: false` for stability. You **must** manually create the search index in MongoDB Atlas with the correct dimensions (768) to match the local embedding model.

1.  Log in to **MongoDB Atlas**.
2.  Navigate to **Atlas Search** tab -> **Create Search Index**.
3.  Choose **JSON Editor**.
4.  Select your database and the `vector_store` collection.
5.  **Index Name:** `vector_index`
6.  **Configuration:** Paste the following JSON exactly:
    ```json
    {
      "fields": [
        {
          "numDimensions": 768,
          "path": "embedding",
          "similarity": "cosine",
          "type": "vector"
        }
      ]
    }
    ```
7.  Click **Create** and wait for the status to turn **Active**.

### 3. Application Configuration
Open `src/main/resources/application.yaml`.
Ensure your MongoDB Connection String is correct:

```yaml
spring:
  data:
    mongodb:
      uri: mongodb+srv://<USERNAME>:<PASSWORD>@<CLUSTER>.mongodb.net/mercenary
  ai:
    ollama:
      base-url: http://localhost:11434
      embedding:
        model: nomic-embed-text  # CRITICAL: Forces use of 768-dim model