# Workspace / Multi-Tenant Isolation Plan (Draft)

Last updated: 2026-02-03
Owner: Platform
Status: In progress (design + staged rollout)

## Objective
Introduce workspace-level isolation for non-regulated editions to support multi-tenant and MSP-style deployments, without reducing HIPAA (Medical) or SCIF/Clearance (Government) compliance.

## Non-goals
- Do not change Medical or Government editions to multi-tenant. They remain single-workspace with strict isolation and no cross-tenant UI.
- No changes to on-prem air-gap posture or clearance enforcement.

## Compliance Constraints
- **Medical**: single workspace only, HIPAA strict mode remains enforced. No cross-tenant sharing.
- **Government**: single workspace only, clearance/SCIF constraints remain enforced. No cross-tenant sharing.
- Cross-workspace data access is always denied, even for admin, unless explicitly operating in a global admin capacity (planned only for non-regulated editions).

## Core Concepts
- **Workspace**: A tenant boundary with its own users, datasets, cases, and configurations.
- **Membership**: Users belong to one or more workspaces (non-regulated editions only).
- **Isolation**: All persisted artifacts tagged with `workspaceId` and filtered by the active workspace.

## Phase 0 (Design + Alignment) - Now
Deliverables:
- This plan document.
- Agreement on scopes, API contracts, and UI affordances.
- Review of regulated edition constraints (Medical/Gov).

## Phase 1 (Backend Data Tagging)
Goal: Add `workspaceId` tagging to persisted entities and enforce query-level isolation.

Planned scope:
- Data models to tag:
  - chat history
  - feedback
  - cases
  - audit log
  - tokenization vault entries (if stored per tenant)
  - connector sync state
  - ingestion metadata / vector store documents
- Access enforcement:
  - Resolve `workspaceId` from request context (header + user membership).
  - Default workspace for existing data (`workspace_default`).
  - Add strict filters in queries and services.

## Phase 2 (Workspace Management APIs)
Goal: Introduce workspace admin APIs for non-regulated editions.

Planned APIs (non-regulated only):
- `POST /api/workspaces` create workspace
- `GET /api/workspaces` list memberships
- `POST /api/workspaces/{id}/members` add user
- `DELETE /api/workspaces/{id}/members/{userId}` remove user

Security rules:
- Only ADMIN can create/delete workspaces.
- Users must be explicit members to access workspace data.

Implementation status (2026-02-03):
- API endpoints implemented with edition gating (disabled for Medical/Gov by default).
- Workspace metadata persisted in `workspaces` collection.
- Member add/remove updates user workspaceIds.
- UI switcher remains Phase 3.

## Phase 3 (UI Workspace Switcher)
Goal: Provide workspace switcher and scoped UI contexts.

- Add workspace selector to header (hidden for Medical/Gov).
- Persist last workspace selection in local storage per user.
- Disable cross-workspace case sharing.

## Phase 4 (Operational Controls)
Goal: Add tenant-level quotas and export/reporting controls.

- Per-workspace limits (documents, queries/day, storage).
- Per-workspace audit export.
- Workspace-scoped connector sync.

## Migration Plan
- Backfill all existing records with `workspace_default`.
- Force all users to `workspace_default`.
- Provide optional migration tool for splitting data into new workspaces (non-regulated only).

## Testing Plan (Minimum)
- Unit tests for workspace scope filters.
- Integration tests for cross-workspace access denial.
- UI tests for workspace switching and selector hiding in regulated editions.

## Open Questions
- Do we require a global super-admin in non-regulated editions?
- Do we need workspace-specific encryption keys and retention policies from day one?
- Should API keys be scoped per workspace?
