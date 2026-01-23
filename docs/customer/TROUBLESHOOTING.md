# Troubleshooting

## MongoDB authentication failed
Symptoms:
- "Authentication failed" in logs
- Document count fails at startup

Actions:
- Verify MONGODB_URI includes the correct user, password, and database
- Verify the user exists in the mercenary database
- Test with mongosh using --authenticationDatabase mercenary

## MongoDB connection refused
Symptoms:
- ECONNREFUSED 127.0.0.1:27017

Actions:
- Start the MongoDB service
- Confirm mongod.cfg bindIp and port
- Confirm Windows firewall rules allow local loopback

## Ollama offline
Symptoms:
- Telemetry shows llmOnline=false

Actions:
- Start Ollama (ollama serve)
- Verify OLLAMA_URL
- Ensure required models are pulled

## DEV mode warnings in production
Symptoms:
- "DEV MODE ACTIVE" warnings

Actions:
- Set APP_PROFILE to standard, enterprise, or govcloud
- Set AUTH_MODE accordingly

## Tokenization vault warning
Symptoms:
- "Using randomly generated key" log messages

Actions:
- Set app.tokenization.secret-key (env: APP_TOKENIZATION_SECRET_KEY)

## Upload fails
Symptoms:
- Ingestion returns failure

Actions:
- Ensure file size <= 50MB
- Remove password protection from PDFs
- Check user has INGEST permission
