@echo off
set APP_PROFILE=dev
set AUTH_MODE=DEV
set MONGODB_URI=mongodb://localhost:27017/mercenary
set OLLAMA_URL=http://localhost:11434
set LLM_MODEL=llama3.1:8b
set EMBEDDING_MODEL=nomic-embed-text
cd /d D:\Projects\mercenary
call gradlew bootRun > D:\Projects\mercenary\bootrun-status.log 2>&1
