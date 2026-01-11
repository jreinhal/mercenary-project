# SENTINEL Intelligence Platform - User Guide

**Version:** 1.0.0
**Classification:** UNCLASSIFIED // FOR OFFICIAL USE ONLY

## 1. Quick Start

### Windows Users
1.  Double-click `start_sentinel.bat`.
2.  When prompted, select your deployment mode:
    *   **[1] Commercial:** For Medical, Legal, and Financial use.
    *   **[2] Government:** For DoD/IC use (Requires CAC/PIV).
3.  The application will launch in your default web browser at `http://localhost:8080`.

### Mac/Linux Users
1.  Open Terminal.
2.  Run `./start_sentinel.sh`.
3.  Follow the interactive prompts to select your mode.

---

## 2. Interface & Sectors

SENTINEL adapts its interface and terminology to your specific mission profile. Use the **Sector Selector** in the command bar to switch modes instantly.

| Sector | Theme | Terminology | Use Case |
| :--- | :--- | :--- | :--- |
| **DEFENSE** | **Dark / Terminal** | Intelligence Platform | Mission planning, threat analysis, tactical query. |
| **MEDICAL** | **Clinical (White/Blue)** | Clinical Assistant | Patient diagnostics, research summarization, drug interactions. |
| **LEGAL** | **Professional (Serif)** | Legal Research Aid | Case law retrieval, precedent analysis, contract review. |
| **FINANCIAL** | **Professional (Serif)** | Market Analyst | Market trend analysis, ticker research, risk assessment. |

---

## 3. How to Query

SENTINEL uses a **"Glass Box"** reasoning engine. It doesn't just give you an answer; it shows you *how* it found it.

### Best Practices
*   **Be Specific:** Instead of "Tell me about the patient," ask "Summarize the patient's history of cardiac issues from the uploaded PDF."
*   **Upload Context:** Drag and drop relevant files (PDF, TXT, MD) into the "Upload Intelligence" zone before asking questions.
*   **Check Citations:** Every fact in the response is followed by a citation like `[report.pdf]`. Click it to verify the source document.

### Understanding Response Confidence
*   **High (Green):** Answer is directly supported by multiple high-relevance documents.
*   **Medium (Yellow):** Answer is inferred or supported by fewer documents.
*   **Low (Red):** Information is scarce. Proceed with caution.

---

## 4. Troubleshooting

**"System Offline / LLM Unreachable"**
*   The system has entered "Offline Mode." It will still retrieve documents but may not be able to synthesize fluent answers. It will provide raw excerpts instead.

**"Access Denied"**
*   Ensure you are logged in to the correct mode (Gov vs Commercial).
*   Check your clearance level. You cannot access "TOP SECRET" documents from an "UNCLASSIFIED" terminal.

**Display Issues**
*   If the screen is blank, try refreshing the page (`Ctrl + R`) or clearing your browser cache.

---
*Powered by Project Mercenary HyperGraph Engine*
