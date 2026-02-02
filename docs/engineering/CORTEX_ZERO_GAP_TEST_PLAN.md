# CORTEX Zero-Gap E2E Test Plan (Living Document)

Last updated: 2026-02-02

This document is a living test protocol derived from the provided prompt. It is intended to be refined over time.

## Required Reading (must appear first)
If any file is unavailable, request access before proceeding.

1) `AGENTS.md` (if present in the target repository, it must be read first)
2) `D:\Projects\reference-repos\knowledge\skills\AGENTS.md`
3) `D:\Projects\reference-repos\knowledge\google-genai-skills\skills\google-adk-python\references\agents.md`
4) `D:\Projects\reference-repos\knowledge\OpenSpec\AGENTS.md`
5) `D:\Projects\reference-repos\skills\antigravity-awesome-skills\skills\postgres-best-practices\AGENTS.md`
6) `D:\Projects\reference-repos\skills\antigravity-awesome-skills\skills\react-best-practices\AGENTS.md`
7) `D:\Projects\reference-repos\knowledge\wg-best-practices-os-developers\docs\Secure-Coding-Guide-for-Python\08_coding_standards\pyscg-0035\README.md`
8) `D:\Projects\reference-repos\knowledge\OpenSpec\openspec\changes\archive\2025-08-11-add-complexity-guidelines\specs\openspec-docs\README.md`
9) `D:\Projects\reference-repos\knowledge\wg-best-practices-os-developers\docs\Secure-Coding-Guide-for-Python\08_coding_standards\pyscg-0034\README.md`
10) `D:\Projects\reference-repos\knowledge\google-genai-skills\skills\deep-research\SKILL.md`
11) `D:\Projects\reference-repos\knowledge\wg-best-practices-os-developers\docs\Secure-Coding-Guide-for-Python\08_coding_standards\pyscg-0033\README.md`
12) `D:\Projects\reference-repos\knowledge\google-genai-skills\skills\nano-banana-use\SKILL.md`
13) `D:\Projects\reference-repos\knowledge\wg-best-practices-os-developers\docs\Existing Guidelines for Developing and Distributing Secure Software.md`
14) `D:\Projects\reference-repos\knowledge\AeyeGuard_cmd\react\README.md`
15) `D:\Projects\reference-repos\knowledge\wg-best-practices-os-developers\docs\Secure-Coding-Guide-for-Python\08_coding_standards\pyscg-0037\README.md`
16) `D:\Projects\reference-repos\skills\antigravity-awesome-skills\skills\javascript-typescript-typescript-scaffold\SKILL.md`
17) `D:\Projects\reference-repos\skills\antigravity-awesome-skills\skills\frontend-dev-guidelines\SKILL.md`
18) `D:\Projects\reference-repos\skills\antigravity-awesome-skills\skills\ui-ux-designer\SKILL.md`
19) `D:\Projects\reference-repos\skills\antigravity-awesome-skills\skills\mobile-design\SKILL.md`
20) `D:\Projects\reference-repos\skills\antigravity-awesome-skills\skills\mobile-design\platform-android.md`
21) `D:\Projects\reference-repos\skills\antigravity-awesome-skills\skills\mobile-design\platform-ios.md`
22) `D:\Projects\reference-repos\skills\antigravity-awesome-skills\skills\react-best-practices\SKILL.md`
23) `D:\Projects\reference-repos\skills\antigravity-awesome-skills\skills\ios-developer\SKILL.md`
24) `D:\Projects\reference-repos\skills\antigravity-awesome-skills\skills\tailwind-design-system\SKILL.md`
25) `D:\Projects\reference-repos\skills\antigravity-awesome-skills\skills\tailwind-design-system\resources\implementation-playbook.md`
26) `D:\Projects\reference-repos\tools\lobehub\src\features\CommandMenu\README.md`
27) `D:\Projects\reference-repos\tools\lobehub\packages\builtin-agents\src\agents\page-agent\README.md`
28) `D:\Projects\reference-repos\tools\knowledge-work-plugins\product-management\skills\metrics-tracking\SKILL.md`
29) `D:\Projects\reference-repos\tools\lobehub\src\tools\artifacts\systemRole.ts`
30) `D:\Projects\reference-repos\tools\knowledge-work-plugins\customer-support\skills\knowledge-management\SKILL.md`
31) `D:\Projects\reference-repos\tools\knowledge-work-plugins\marketing\skills\brand-voice\SKILL.md`
32) `D:\Projects\reference-repos\tools\knowledge-work-plugins\customer-support\skills\ticket-triage\SKILL.md`
33) `D:\Projects\reference-repos\tools\knowledge-work-plugins\product-management\skills\user-research-synthesis\SKILL.md`
34) `D:\Projects\reference-repos\tools\knowledge-work-plugins\customer-support\skills\customer-research\SKILL.md`
35) `D:\Projects\reference-repos\tools\knowledge-work-plugins\product-management\skills\competitive-analysis\SKILL.md`
36) `D:\Projects\reference-repos\tools\knowledge-work-plugins\legal\skills\contract-review\SKILL.md`
37) `D:\Projects\reference-repos\tools\knowledge-work-plugins\product-management\skills\roadmap-management\SKILL.md`

## Application Context
CORTEX (Centralized Orchestration & Repository Training for Expert eXecution) is a local-first AI platform with:
- React 19 / Tailwind CSS 4 frontend
- Node.js / Express backend
- Core features: spawning specialized AI agents, managing local reference repositories (Knowledge, Skills, Tools, Agents), and generating structured "Flight Plans" in Markdown

## Critical Path & Functional Logic
1) Setup & Config
   - Verify First-Run Wizard or Settings panel correctly updates `config.json`.
   - Verify repository root connects and persists.

2) Agent Factory Flow
   - Enter a complex goal (e.g., "Audit auth module for security").
   - Verify real-time status timeline updates.
   - Verify Flight Plan generation and "Copy to Clipboard" functionality.

3) Decision Matrix Validation
   - Confirm the Flight Plan includes a Decision Matrix section:
     - Retrieval gate
     - Query expansion
     - RAG-Fusion
     - Hybrid retrieval
     - RRF fusion

4) Repository Management
   - Perform a "Smart Clone" and a "System Scan".
   - Verify repositories are categorized (Agents, Skills, Knowledge, Tools).
   - Verify folder sizes are accurate.

5) Evaluations & Runs
   - Create a dataset in the Evaluation Lab.
   - Run evaluation against a recent spawn.
   - Verify scorecard grading (including LLM rubric grading if enabled).

## Manual UI & Visual Integrity Audit
1) Boundary & Focus Checks
   - Knowledge Base view: ensure "Add Repository" label does not overlap border or focus ring.
   - Input focus rings remain inside fields and do not obscure text.

2) Navigation State
   - Sidebar active state highlights current view.
   - Page header updates correctly.

3) Real-Time Feedback
   - System logs persist across view changes.
   - Telemetry/analytics increment after successful spawns.

## UX & Edge Case Hunting
1) Ambiguity Handling
   - Input an ambiguous goal and verify the platform flags "Requires Review" or low-confidence routing.

2) Error States
   - Invalid repository URLs trigger notice system and log entries.
   - Duplicate repository additions surface warnings and logs.

## Output Requirements
- Step-by-step execution log with every click and input string.
- Functional pass/fail for core workflows (Spawn, Clone, Scan, Evaluate).
- Visual/UX bug log (overlapping text, animation jank, color contrast issues).
- Telemetry check (verify totalSpawns and recent sessions updated).

## Constraints
- Treat `AGENTS.md` instructions as highest priority context if present.
- If any required reading is unavailable, request access and pause execution.
- Use full UI interactions; do not rely on curl-only validation.
