# SENTINEL // INTELLIGENCE PLATFORM (v1.0.0)

## 1. System Abstract
The Sentinel Platform is a secure, high-throughput Intelligence Augmentation system designed for sensitive enterprise environments. It utilizes a **Hybrid Reasoning Architecture** to combine real-time synchronous querying with deep-context RAG (Retrieval-Augmented Generation).

**Core Capabilities:**
* **Secure Ingestion:** Local vectorization of PDF, TXT, and MD dossiers.
* **Persona Protocol:** Adaptive analytical lenses (Defense, Legal, Medical, Finance).
* **Zero-Day Security:** Integrated "Unauthorized" failsafes for credential protection.
* **Operator Support:** Integrated field manual and troubleshooting protocols.

---

## 2. Technical Architecture
* **Core Engine:** Java 21 (Spring Boot 3.2)
* **Frontend:** Server-Side Rendered HTML5/CSS3 (Dark Mode / Tactical UI)
* **Vector Backend:** MongoDB / Hypergraph Service
* **Neural Interface:** Ollama (Mistral / Llama3)
* **Performance:** Tested at ~2,500 RPS (Requests Per Second) throughput.

---

## 3. Build & Deployment
The system builds into a single, portable executable artifact (`.jar`).

### Prerequisites
* Java JDK 21+
* Ollama (Running locally on Port 11434)
* MongoDB (Running locally on Port 27017)

### Build Command (Gold Master)
To generate the production executable:
```powershell
./gradlew bootJar