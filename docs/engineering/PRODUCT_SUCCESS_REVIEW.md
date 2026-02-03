# Product Success Review - SENTINEL Intelligence Platform

Last updated: 2026-02-02

## Objective
Scan the software and identify what needs to change to maximize product-market fit and become a top-selling product, with a focus on UI/UX and marketing readiness.

## Product Summary (Current)
SENTINEL is a secure, air-gap compatible RAG platform aimed at enterprise and government deployments. It ships with a single-page web dashboard, multi-edition response policy (trial, professional, medical, government), and a rich set of configurable RAG techniques (Hybrid, MegaRAG, AdaptiveRAG, etc.).

## Strengths (Differentiators)
- Air-gap and govcloud posture with CAC/OIDC/standard auth modes.
- Strong emphasis on auditability, citations, and evidence excerpts in regulated editions.
- Advanced RAG feature toggles and graph/entity exploration.
- Clear edition-based policy controls and compliance documentation.

## Gaps That Limit "Top-Selling" Potential
### Product/Platform
- Limited enterprise connectors (no native SharePoint/Confluence/Jira/ServiceNow/S3/Box, etc.).
- Single-org posture (no visible multi-tenant controls, workspace isolation, or per-tenant quotas).
- No formal eval harness surfaced in the UI for measuring response quality over time.
- Onboarding relies on manual ingestion rather than guided or automated connectors.

### UX / Workflow
- UI is feature-rich but primarily a single workspace; no explicit "investigation" workflow with case/timeline artifacts.
- Graph exploration exists but lacks built-in "investigation workbench" flow (triage -> investigation -> report).
- Limited collaboration/case-sharing workflows (notes, assignments, approvals) visible in UI.

### GTM / Marketing
- Positioning is strong for air-gap and regulated markets, but mainstream enterprise buyers expect faster time-to-value and prebuilt connectors.
- Demo stories should anchor around measurable outcomes (time-to-answer, reduced analyst time, compliance wins).

## UI Direction (Online Research, Pattern Matches)
A best-in-class UI for this type of platform typically blends:
- Investigation workbench style views (centralized incident context).
- Timeline-centric investigations.
- Graph exploration that is approachable for non-technical analysts.

Recommended inspirations:
- Splunk ES Investigation Workbench: centralizes context and investigation in one place.
- Elastic Security: separate "Explore" views (hosts/network/users) plus "Timeline" investigations.
- Neo4j Bloom: code-less graph exploration and perspective-based graph views.
- Maltego: rich graph visualization controls (layout, view tuning, link labels).

## Marketing Success Outlook
- Strong potential in regulated sectors (government/medical) where air-gap + auditability are critical.
- To be "top-selling" beyond niche regulated markets, the product needs faster onboarding, more integrations, and collaboration workflows.
- Success likelihood improves sharply if the UI workflow supports investigation timelines, case artifacts, and report export.

## Recommendations (Prioritized)
### 0-3 Months (Fast Wins)
- Add guided onboarding with sample datasets and prebuilt demo scenarios.
- Create investigation workflow UI: case, timeline, notes, and export.
- Add clear UX path for graph + source + narrative output.

### 3-6 Months (Mid-Term)
- Introduce top 3 enterprise connectors (SharePoint, Confluence, S3).
- Add eval harness in admin UI with quality metrics per sector.
- Add collaboration: shared cases, review/approval, redaction review.

### 6-12 Months (Strategic)
- Multi-tenant/workspace isolation for MSP or multi-org deployments.
- Marketplace for connectors and sector-specific pipelines.
- Expanded reporting for compliance and executive summaries.

## Implementation Status (2026-02-02)
### Completed (Fast Wins)
- Guided onboarding with demo scenarios and admin-only synthetic data loader.
- Case/timeline workspace with export and compliance gating.
- Graph + source + case quick actions in the onboarding panel.

### Completed (Mid-Term)
- Enterprise connectors: SharePoint, Confluence, S3 (admin-only; disabled for regulated editions by default).
- Eval harness UI (admin-only) for baseline query suites and quality signals.
- Collaboration workflows (shared cases, review/approval, redaction review) implemented for non-regulated editions.

### Planned (Strategic)
- Workspace admin UI + tenant quotas + migration tooling (Phase 2).
- Marketplace UI + additional sector pipelines/connectors.
- Reporting UI, scheduled exports, and SLA dashboards.

### Now in Motion
- Workspace isolation design + staged rollout plan drafted in `docs/engineering/plans/WORKSPACE_ISOLATION.md`.

### In Progress (Strategic)
- Workspace isolation Phase 1 implemented (workspaceId tagging + query filters + header-based selection; UI switcher pending).
- Workspace management APIs (Phase 2) implemented (create/list/membership; non-regulated only).
- Workspace selector added to Settings sidebar (Phase 3 partial; regulated editions hidden).
- Connector marketplace API added (admin-only catalog endpoint).
- Compliance/executive reporting pack added (admin-only executive report endpoint).

## References (UI/UX Inspiration)
- https://www.splunk.com/en-us/blog/security/use-investigation-workbench-to-reduce-time-to-contain-and-time-to-remediate.html
- https://www.elastic.co/docs/solutions/security/get-started/elastic-security-ui
- https://neo4j.com/docs/desktop/current/explore/
- https://neo4j.com/bloom/
- https://support.maltego.com/en/support/solutions/articles/15000010458-view-tab
