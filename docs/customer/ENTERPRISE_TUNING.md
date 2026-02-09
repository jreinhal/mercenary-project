# Enterprise Tuning Guide

This guide covers performance tuning for high-concurrency SENTINEL deployments.

## Activating the Enterprise Profile

Set `APP_PROFILE=enterprise` to load `application-enterprise.yaml`, which provides production-tuned defaults. All values can be overridden via environment variables.

## Thread Pool Configuration

SENTINEL uses dedicated thread pools for RAG retrieval and reranking operations.

### RAG Executor

| Setting | Env Variable | Default | Enterprise Default |
|---------|-------------|---------|-------------------|
| Core threads | RAG_CORE_THREADS | 8 | 32 |
| Max threads | RAG_MAX_THREADS | 16 | 64 |
| Queue capacity | RAG_QUEUE_CAPACITY | 500 | 2000 |
| Future timeout (seconds) | RAG_FUTURE_TIMEOUT_SECONDS | 10 | 15 |

### Reranker Executor

| Setting | Env Variable | Default | Enterprise Default |
|---------|-------------|---------|-------------------|
| Thread count | RERANKER_THREADS | 8 | 16 |

### Sizing Guidance

| Concurrent Users | RAG Core | RAG Max | Queue | Reranker |
|-----------------|----------|---------|-------|----------|
| 1-10 | 8 | 16 | 500 | 8 |
| 10-50 | 16 | 32 | 1000 | 12 |
| 50-200 | 32 | 64 | 2000 | 16 |
| 200+ | 48 | 96 | 4000 | 24 |

### Rejection Handling

When thread pools are saturated, tasks are rejected with a logged warning rather than blocking the HTTP thread. The rejection counter is exposed via the admin stats endpoint. Services degrade gracefully by returning partial results.

### Monitoring

Thread pool stats are available at `/api/admin/thread-pool-stats` (ADMIN role required):
- Active threads, pool size, queue size
- Completed task count, rejection count
- Available for both RAG and reranker executors

## Tomcat (HTTP Server)

| Setting | Default | Enterprise Default |
|---------|---------|-------------------|
| Max threads | 200 | 400 |
| Max connections | 200 | 400 |
| Accept count | 100 | 200 |

## MongoDB Connection Pool

MongoDB connection pool is configured via URI query parameters. Recommended production URI:

```
MONGODB_URI=mongodb://user:pass@host:27017/sentinel?maxPoolSize=100&minPoolSize=10&maxIdleTimeMS=30000&waitQueueTimeoutMS=5000
```

| Parameter | Recommended | Description |
|-----------|------------|-------------|
| maxPoolSize | 100 | Maximum connections |
| minPoolSize | 10 | Minimum idle connections |
| maxIdleTimeMS | 30000 | Close idle connections after 30s |
| waitQueueTimeoutMS | 5000 | Fail fast if pool exhausted |

## Docker Resource Limits

For containerized deployments, set appropriate resource limits:

```yaml
services:
  sentinel:
    deploy:
      resources:
        limits:
          cpus: '4'
          memory: 4G
        reservations:
          cpus: '2'
          memory: 2G
    environment:
      - JAVA_OPTS=-Xmx3g -Xms2g
```

### Sizing by Deployment Scale

| Scale | CPUs | Memory | Java Heap | MongoDB |
|-------|------|--------|-----------|---------|
| Small (1-10 users) | 2 | 2G | -Xmx1500m | 2G RAM |
| Medium (10-50 users) | 4 | 4G | -Xmx3g | 4G RAM |
| Large (50-200 users) | 8 | 8G | -Xmx6g | 8G RAM |
| XL (200+ users) | 16 | 16G | -Xmx12g | 16G RAM |

## License Configuration

For licensed deployments, configure the signing secret:

```
LICENSE_SIGNING_SECRET=<provided-by-vendor>
sentinel.license.key=<license-key>
```

See SECURITY.md for the license validation behavior matrix.

## Connector Sync Schedule

Enable automatic document synchronization from external sources:

```
CONNECTOR_SYNC_ENABLED=true
CONNECTOR_SYNC_CRON=0 0 2 * * ?
```

For high-volume connectors, consider off-peak scheduling and monitoring the sync duration in application logs.
