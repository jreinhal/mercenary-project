# RAG Features

This document lists major RAG services and their default enablement.

## Enabled by default
- RAGPart (RAGPART_ENABLED=true)
- QuCoRAG (QUCORAG_ENABLED=true)
- MegaRAG (MEGARAG_ENABLED=true)
- MiA-RAG (MIARAG_ENABLED=true)
- Bidirectional RAG (BIRAG_ENABLED=true)
- Hybrid RAG (HYBRIDRAG_ENABLED=true)
- AdaptiveRAG (ADAPTIVERAG_ENABLED=true)
- CRAG (CRAG_ENABLED=true)

## Disabled by default
- HGMem (HGMEM_ENABLED=false)
- HiFi-RAG (HIFIRAG_ENABLED=false)
- Graph-O1 (GRAPHO1_ENABLED=false)
- HyDE (HYDE_ENABLED=false)
- Self-RAG (SELFRAG_ENABLED=false)
- Agentic orchestrator (AGENTIC_ENABLED=false)

## Notes
- Feature toggles live in application.yaml under sentinel.*
- Several features add significant latency; enable selectively
