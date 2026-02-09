@echo off
set APP_PROFILE=dev
set AUTH_MODE=DEV
if "%MONGODB_URI%"=="" set MONGODB_URI=mongodb://localhost:27017/mercenary
if "%OLLAMA_URL%"=="" set OLLAMA_URL=http://localhost:11434
if "%LLM_MODEL%"=="" set LLM_MODEL=llama3.1:8b
if "%EMBEDDING_MODEL%"=="" set EMBEDDING_MODEL=nomic-embed-text
cd /d D:\Projects\mercenary
call gradlew bootRun > D:\Projects\mercenary\bootrun-status.log 2>&1
