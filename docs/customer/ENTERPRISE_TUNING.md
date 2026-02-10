# Enterprise Performance Tuning Guide

This guide covers production-grade configuration for SENTINEL deployments serving 500+ concurrent users on dedicated server hardware (16+ CPU cores, 32GB+ RAM).

## Quick Start

The `enterprise` Spring profile automatically loads production-tuned defaults from `application-enterprise.yaml`. You can further override any setting via environment variables.

```bash
# Activate enterprise profile (also enables OIDC authentication)
APP_PROFILE=enterprise
```

## Thread Pool Configuration

SENTINEL uses dedicated thread pools for RAG retrieval and document reranking. Default settings are tuned for local development (4-8 threads). Enterprise deployments should scale these based on CPU core count.

### RAG Thread Pool

The RAG executor handles parallel vector store queries and LLM calls (I/O-bound). Recommended: **2x CPU cores**.

| Variable | Default (Dev) | Enterprise Default | Description |
|----------|---------------|-------------------|-------------|
| `RAG_CORE_THREADS` | 4 | 32 | Minimum threads kept alive |
| `RAG_MAX_THREADS` | 8 | 64 | Maximum threads under load |
| `RAG_QUEUE_CAPACITY` | 200 | 2000 | Pending tasks before rejection |
| `RAG_FUTURE_TIMEOUT_SECONDS` | 8 | 15 | Per-query timeout (fail fast) |

### Reranker Thread Pool

The reranker executor handles LLM-based cross-encoder scoring (CPU-intensive). Recommended: **1x CPU cores**.

| Variable | Default (Dev) | Enterprise Default | Description |
|----------|---------------|-------------------|-------------|
| `RERANKER_THREADS` | 4 | 16 | Core and max threads (fixed pool) |

### Overload Protection

When the thread pool queue is full, new tasks are **rejected** (not run on the caller thread). This prevents HTTP request threads from being blocked by slow RAG operations, which would otherwise freeze the UI for all users.

Rejected tasks return degraded results (fewer search variants or skipped reranking) rather than hanging the request. Monitor rejection counts via the admin endpoint.

## Tomcat Configuration

Match Tomcat thread capacity to your RAG thread pool to avoid bottlenecks at the HTTP layer.

| Variable | Default | Enterprise Default | Description |
|----------|---------|-------------------|-------------|
| `TOMCAT_MAX_THREADS` | 200 | 400 | Max HTTP request threads |
| `TOMCAT_MAX_CONNECTIONS` | 200 | 400 | Max TCP connections |
| `TOMCAT_ACCEPT_COUNT` | 100 | 200 | TCP backlog queue size |

## MongoDB Connection Pool

Spring Data MongoDB configures connection pool settings via the MongoDB URI. Add query parameters to your `MONGODB_URI` for enterprise deployments:

```bash
MONGODB_URI=mongodb://host:27017/sentinel?maxPoolSize=100&minPoolSize=10&maxIdleTimeMS=30000&waitQueueTimeoutMS=5000&connectTimeoutMS=5000&socketTimeoutMS=30000
```

| Parameter | Recommended | Description |
|-----------|-------------|-------------|
| `maxPoolSize` | 100 | Max connections (match RAG thread count) |
| `minPoolSize` | 10 | Keep warm connections for burst handling |
| `maxIdleTimeMS` | 30000 | Close idle connections after 30s |
| `waitQueueTimeoutMS` | 5000 | Fail fast if pool exhausted |
| `connectTimeoutMS` | 5000 | Connection establishment timeout |
| `socketTimeoutMS` | 30000 | Socket read timeout |

## Docker Compose Reference

```yaml
services:
  sentinel-app:
    environment:
      APP_PROFILE: enterprise
      # RAG Thread Pool (2x core count for I/O bound tasks)
      RAG_CORE_THREADS: 32
      RAG_MAX_THREADS: 64
      RAG_QUEUE_CAPACITY: 2000
      RAG_FUTURE_TIMEOUT_SECONDS: 15
      # Reranker (1x core count for CPU-intensive tasks)
      RERANKER_THREADS: 16
      # Tomcat
      TOMCAT_MAX_THREADS: 400
      TOMCAT_MAX_CONNECTIONS: 400
      TOMCAT_ACCEPT_COUNT: 200
      # MongoDB with pool settings
      MONGODB_URI: mongodb://mongo:27017/sentinel?maxPoolSize=100&minPoolSize=10&maxIdleTimeMS=30000&waitQueueTimeoutMS=5000
      # Vector Store (use Atlas for scale)
      SENTINEL_FORCE_ATLAS_VECTOR_STORE: "true"
    deploy:
      resources:
        limits:
          cpus: "16"
          memory: 32G
        reservations:
          cpus: "8"
          memory: 16G
```

## Monitoring

### Thread Pool Stats Endpoint

```
GET /api/admin/thread-pool-stats
Authorization: (ADMIN role required)
```

Returns real-time pool metrics for both executors:

```json
{
  "ragExecutor": {
    "corePoolSize": 32,
    "maxPoolSize": 64,
    "activeThreads": 12,
    "currentPoolSize": 32,
    "largestPoolSize": 48,
    "queueSize": 3,
    "queueRemainingCapacity": 1997,
    "completedTaskCount": 15420,
    "totalTaskCount": 15435,
    "rejectionCount": 0
  },
  "rerankerExecutor": { ... }
}
```

**Key metrics to watch:**
- `rejectionCount > 0` — Pool is overloaded; increase `MAX_THREADS` or `QUEUE_CAPACITY`
- `queueSize` approaching `queueRemainingCapacity` — Near saturation
- `activeThreads == maxPoolSize` — All threads busy; queries may queue
- `largestPoolSize < maxPoolSize` — Pool has headroom; consider reducing `maxPoolSize` to save resources

## Sizing Guide

| Deployment Size | Users | CPU Cores | RAG Threads | Queue | Reranker |
|----------------|-------|-----------|-------------|-------|----------|
| Development | 1-5 | 2-4 | 4/8 | 200 | 4 |
| Small Team | 10-50 | 8 | 16/32 | 500 | 8 |
| Department | 50-200 | 16 | 32/64 | 2000 | 16 |
| Enterprise | 500+ | 32+ | 64/128 | 4000 | 32 |
