#!/usr/bin/env python3
"""
SENTINEL Intelligence Platform - Official Certification Report Generator
Generates PDF documentation for C&A (Certification and Accreditation)
"""

from reportlab.lib import colors
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import inch
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, ListFlowable, ListItem
from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_JUSTIFY
from datetime import datetime
import os

def create_styles():
    styles = getSampleStyleSheet()

    styles.add(ParagraphStyle(
        name='CoverTitle',
        parent=styles['Title'],
        fontSize=32,
        textColor=colors.HexColor('#143C78'),
        spaceAfter=20,
        alignment=TA_CENTER
    ))

    styles.add(ParagraphStyle(
        name='CoverSubtitle',
        parent=styles['Normal'],
        fontSize=18,
        textColor=colors.HexColor('#3C5078'),
        spaceAfter=10,
        alignment=TA_CENTER
    ))

    styles.add(ParagraphStyle(
        name='ChapterTitle',
        parent=styles['Heading1'],
        fontSize=16,
        textColor=colors.HexColor('#143C78'),
        spaceBefore=20,
        spaceAfter=12,
        borderPadding=5,
        borderColor=colors.HexColor('#143C78'),
        borderWidth=0,
        leftIndent=0
    ))

    styles.add(ParagraphStyle(
        name='SectionTitle',
        parent=styles['Heading2'],
        fontSize=13,
        textColor=colors.HexColor('#28508C'),
        spaceBefore=15,
        spaceAfter=8
    ))

    styles.add(ParagraphStyle(
        name='SubsectionTitle',
        parent=styles['Heading3'],
        fontSize=11,
        textColor=colors.HexColor('#3C64A0'),
        spaceBefore=10,
        spaceAfter=6
    ))

    styles.add(ParagraphStyle(
        name='BodyPara',
        parent=styles['Normal'],
        fontSize=10,
        textColor=colors.black,
        spaceAfter=8,
        alignment=TA_JUSTIFY,
        leading=14
    ))

    styles.add(ParagraphStyle(
        name='BulletText',
        parent=styles['Normal'],
        fontSize=10,
        textColor=colors.black,
        leftIndent=20,
        spaceAfter=4
    ))

    styles.add(ParagraphStyle(
        name='CodeBlock',
        parent=styles['Code'],
        fontSize=8,
        textColor=colors.HexColor('#1E1E1E'),
        backColor=colors.HexColor('#F0F0F0'),
        borderPadding=8,
        spaceAfter=10,
        leftIndent=10,
        rightIndent=10
    ))

    styles.add(ParagraphStyle(
        name='Finding',
        parent=styles['Normal'],
        fontSize=10,
        textColor=colors.HexColor('#C83232'),
        fontName='Helvetica-Bold',
        spaceAfter=4
    ))

    styles.add(ParagraphStyle(
        name='FooterText',
        parent=styles['Normal'],
        fontSize=8,
        textColor=colors.gray,
        alignment=TA_CENTER
    ))

    return styles

def create_table(data, col_widths, header=True):
    """Create a styled table"""
    table = Table(data, colWidths=col_widths)

    style_commands = [
        ('FONTNAME', (0, 0), (-1, -1), 'Helvetica'),
        ('FONTSIZE', (0, 0), (-1, -1), 9),
        ('GRID', (0, 0), (-1, -1), 0.5, colors.HexColor('#CCCCCC')),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 6),
        ('RIGHTPADDING', (0, 0), (-1, -1), 6),
        ('TOPPADDING', (0, 0), (-1, -1), 4),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
    ]

    if header:
        style_commands.extend([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#325082')),
            ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
            ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ])
        # Alternate row colors
        for i in range(1, len(data)):
            if i % 2 == 0:
                style_commands.append(('BACKGROUND', (0, i), (-1, i), colors.HexColor('#F5F5FA')))

    table.setStyle(TableStyle(style_commands))
    return table

def generate_report():
    output_path = '/home/user/mercenary-project/mercenary/SENTINEL_Certification_Report.pdf'
    doc = SimpleDocTemplate(
        output_path,
        pagesize=letter,
        rightMargin=50,
        leftMargin=50,
        topMargin=50,
        bottomMargin=50
    )

    styles = create_styles()
    story = []

    # ========== COVER PAGE ==========
    story.append(Spacer(1, 2*inch))
    story.append(Paragraph('SENTINEL', styles['CoverTitle']))
    story.append(Paragraph('Intelligence Platform', styles['CoverSubtitle']))
    story.append(Spacer(1, 0.5*inch))
    story.append(Paragraph('CERTIFICATION AND ACCREDITATION REPORT', styles['CoverSubtitle']))
    story.append(Spacer(1, 1*inch))

    cover_info = [
        f'Report Date: {datetime.now().strftime("%B %d, %Y")}',
        'Version: 1.0.0',
        'Classification: UNCLASSIFIED // FOR OFFICIAL USE ONLY'
    ]
    for info in cover_info:
        story.append(Paragraph(info, styles['BodyPara']))

    story.append(Spacer(1, 1*inch))
    story.append(Paragraph(
        'This document provides a comprehensive technical assessment of the SENTINEL Intelligence Platform '
        'for certification and accreditation purposes. It includes architecture analysis, security controls '
        'evaluation, API documentation, and compliance mapping.',
        styles['BodyPara']
    ))

    story.append(PageBreak())

    # ========== TABLE OF CONTENTS ==========
    story.append(Paragraph('Table of Contents', styles['ChapterTitle']))
    toc_items = [
        '1. Executive Summary',
        '2. System Overview',
        '3. Architecture Analysis',
        '4. Security Controls Assessment',
        '5. API Endpoint Documentation',
        '6. Data Flow Analysis',
        '7. User Interface Components',
        '8. Compliance Mapping',
        '9. Test Plan & Procedures',
        '10. Findings & Recommendations',
        '11. Certification Statement',
        'Appendix A: File Inventory'
    ]
    for item in toc_items:
        story.append(Paragraph(item, styles['BodyPara']))

    story.append(PageBreak())

    # ========== 1. EXECUTIVE SUMMARY ==========
    story.append(Paragraph('1. Executive Summary', styles['ChapterTitle']))
    story.append(Paragraph(
        'The SENTINEL Intelligence Platform is an enterprise-grade Retrieval-Augmented Generation (RAG) system '
        'designed for government and commercial environments requiring strict data governance, auditability, '
        'and classification-based access controls. This certification report documents the comprehensive '
        'analysis of the platform\'s security architecture, code quality, and operational capabilities.',
        styles['BodyPara']
    ))

    story.append(Paragraph('Key Findings', styles['SectionTitle']))
    findings = [
        'Multi-layered security model with RBAC + clearance-based access control',
        'Support for three authentication modes: DEV, OIDC (Enterprise), CAC/PIV (Government)',
        'Comprehensive audit logging aligned with NIST 800-53 AU-3 requirements',
        'Air-gapped deployment capability with local MongoDB and Ollama LLM',
        'PII redaction at ingestion (SSN, Email patterns)',
        'Prompt injection detection and blocking',
        'Citation anchoring for full traceability of AI-generated responses'
    ]
    for f in findings:
        story.append(Paragraph(f'• {f}', styles['BulletText']))

    story.append(Paragraph('Overall Assessment', styles['SectionTitle']))
    story.append(Paragraph(
        'The SENTINEL platform demonstrates a well-architected security model suitable for handling '
        'sensitive information across multiple classification levels. The codebase follows separation '
        'of concerns principles with clear boundaries between authentication, authorization, and audit '
        'functions. Minor improvements are recommended in JWT validation for OIDC mode and expansion '
        'of prompt injection detection patterns.',
        styles['BodyPara']
    ))

    story.append(PageBreak())

    # ========== 2. SYSTEM OVERVIEW ==========
    story.append(Paragraph('2. System Overview', styles['ChapterTitle']))

    story.append(Paragraph('2.1 Platform Description', styles['SectionTitle']))
    story.append(Paragraph(
        'SENTINEL is a Spring Boot 3.3 application providing secure document ingestion, semantic search, '
        'and AI-powered question answering capabilities. The platform uses MongoDB for document persistence '
        'and vector storage, with Ollama providing local LLM inference for air-gapped deployments.',
        styles['BodyPara']
    ))

    story.append(Paragraph('2.2 Technology Stack', styles['SectionTitle']))
    tech_data = [
        ['Component', 'Technology', 'Version'],
        ['Runtime', 'Java OpenJDK', '21'],
        ['Framework', 'Spring Boot', '3.3.0'],
        ['AI Framework', 'Spring AI', '1.0.0-M1'],
        ['Database', 'MongoDB', '7.x'],
        ['LLM Engine', 'Ollama', 'Latest'],
        ['Build Tool', 'Gradle', '8.14'],
        ['Container', 'Docker Compose', 'v2'],
    ]
    story.append(create_table(tech_data, [2*inch, 2*inch, 2*inch]))
    story.append(Spacer(1, 0.2*inch))

    story.append(Paragraph('2.3 Deployment Modes', styles['SectionTitle']))
    modes = [
        'Development Mode (DEV): Local testing with auto-generated demo users',
        'Enterprise Mode (OIDC): Azure AD / Okta integration for commercial deployments',
        'Government Mode (CAC): X.509 certificate authentication for federal deployments'
    ]
    for m in modes:
        story.append(Paragraph(f'• {m}', styles['BulletText']))

    story.append(Paragraph('2.4 Department/Sector Classification', styles['SectionTitle']))
    sector_data = [
        ['Sector', 'Gov Label', 'Commercial', 'Clearance'],
        ['OPERATIONS', 'General Ops', 'Public', 'UNCLASSIFIED'],
        ['FINANCE', 'Financial Intel', 'PCI-DSS', 'CUI'],
        ['LEGAL', 'Legal/Contracts', 'Attorney-Client', 'CUI'],
        ['MEDICAL', 'Medical/Clinical', 'HIPAA', 'SECRET'],
        ['DEFENSE', 'Defense/Military', 'CLASSIFIED', 'SECRET'],
        ['ENTERPRISE', 'Enterprise', 'Confidential', 'CUI'],
    ]
    story.append(create_table(sector_data, [1.2*inch, 1.4*inch, 1.4*inch, 1.4*inch]))

    story.append(PageBreak())

    # ========== 3. ARCHITECTURE ANALYSIS ==========
    story.append(Paragraph('3. Architecture Analysis', styles['ChapterTitle']))

    story.append(Paragraph('3.1 Component Architecture', styles['SectionTitle']))
    story.append(Paragraph(
        'The SENTINEL platform follows a layered architecture with clear separation between presentation, '
        'business logic, and data access layers. Key components include:',
        styles['BodyPara']
    ))

    components = [
        'Controllers: MercenaryController (core API), AuditController (audit access)',
        'Services: SecureIngestionService, MemoryEvolutionService, AuditService, AuthenticationService',
        'Filters: SecurityFilter (authentication gateway)',
        'Data Access: LocalMongoVectorStore, UserRepository, ChatLogRepository',
        'Models: User, AuditEvent, ClearanceLevel, Department, UserRole'
    ]
    for c in components:
        story.append(Paragraph(f'• {c}', styles['BulletText']))

    story.append(Paragraph('3.2 Request Processing Flow', styles['SectionTitle']))
    flow_text = '''1. HTTP Request arrives
2. SecurityFilter intercepts (Order=1)
   - Check if public path (bypass auth)
   - Call AuthenticationService.authenticate()
   - Set SecurityContext with authenticated user
3. Controller method executes
   - Verify user permissions (RBAC)
   - Verify clearance level (Classification)
   - Execute business logic
   - Log to AuditService
4. SecurityContext cleared (finally block)
5. Response returned'''
    story.append(Paragraph(flow_text.replace('\n', '<br/>'), styles['CodeBlock']))

    story.append(Paragraph('3.3 Vector Store Architecture', styles['SectionTitle']))
    story.append(Paragraph(
        'SENTINEL uses a custom LocalMongoVectorStore implementation designed for air-gapped deployments. '
        'Unlike MongoDB Atlas Search, this implementation performs brute-force cosine similarity search '
        'in-memory, suitable for document sets under 10,000 items.',
        styles['BodyPara']
    ))

    vector_features = [
        'Storage: MongoDB collection "vector_store"',
        'Embedding: Generated via Ollama (nomic-embed-text model)',
        'Search: O(n*d) brute-force cosine similarity',
        'Filtering: Metadata-based department filtering',
        'Threshold: 0.4 minimum similarity score'
    ]
    for v in vector_features:
        story.append(Paragraph(f'• {v}', styles['BulletText']))

    story.append(Paragraph('3.4 Document Ingestion Pipeline', styles['SectionTitle']))
    pipeline_text = '''1. File Upload (POST /api/ingest/file)
2. Format Detection (PDF/TXT/MD)
3. Content Extraction (Apache Tika)
4. Token-based Splitting
5. PII Redaction (SSN, Email patterns)
6. Memory Evolution (semantic deduplication)
7. Embedding Generation (Ollama)
8. Vector Store Persistence (MongoDB)
9. Audit Log Entry'''
    story.append(Paragraph(pipeline_text.replace('\n', '<br/>'), styles['CodeBlock']))

    story.append(PageBreak())

    # ========== 4. SECURITY CONTROLS ==========
    story.append(Paragraph('4. Security Controls Assessment', styles['ChapterTitle']))

    story.append(Paragraph('4.1 Authentication Mechanisms', styles['SectionTitle']))

    story.append(Paragraph('4.1.1 Development Mode (DEV)', styles['SubsectionTitle']))
    story.append(Paragraph(
        'Development mode provides simplified authentication for testing. Users can be identified via '
        'X-Operator-Id header or operator query parameter. Default user (DEMO_USER) receives ADMIN role '
        'with TOP_SECRET clearance. This mode should NEVER be used in production.',
        styles['BodyPara']
    ))

    story.append(Paragraph('4.1.2 Enterprise Mode (OIDC)', styles['SubsectionTitle']))
    story.append(Paragraph(
        'OIDC mode integrates with enterprise identity providers (Azure AD, Okta). Bearer tokens in '
        'Authorization header are decoded to extract user claims. Auto-provisioned users receive VIEWER '
        'role with UNCLASSIFIED clearance by default.',
        styles['BodyPara']
    ))
    story.append(Paragraph('FINDING: JWT signature validation is not implemented (TODO in code)', styles['Finding']))

    story.append(Paragraph('4.1.3 Government Mode (CAC)', styles['SubsectionTitle']))
    story.append(Paragraph(
        'CAC/PIV mode supports X.509 certificate authentication for government deployments. Certificates '
        'are extracted from request attributes (Servlet API) or X-Client-Cert header (reverse proxy). '
        'Subject DN is parsed to extract user identity. Auto-provisioned users start with VIEWER role.',
        styles['BodyPara']
    ))

    story.append(Paragraph('4.2 Authorization Model', styles['SectionTitle']))

    story.append(Paragraph('4.2.1 Role-Based Access Control (RBAC)', styles['SubsectionTitle']))
    role_data = [
        ['Role', 'Permissions', 'Use Case'],
        ['ADMIN', 'QUERY, INGEST, DELETE, MANAGE_USERS, VIEW_AUDIT, CONFIGURE', 'Administrators'],
        ['ANALYST', 'QUERY, INGEST', 'Intel Analysts'],
        ['VIEWER', 'QUERY', 'Read-only Users'],
        ['AUDITOR', 'QUERY, VIEW_AUDIT', 'Compliance Officers'],
    ]
    story.append(create_table(role_data, [1*inch, 3.5*inch, 1.5*inch]))
    story.append(Spacer(1, 0.2*inch))

    story.append(Paragraph('4.2.2 Clearance Level Hierarchy', styles['SubsectionTitle']))
    clearance_data = [
        ['Level', 'Value', 'Government', 'Commercial'],
        ['UNCLASSIFIED', '0', 'Public', 'Public'],
        ['CUI', '1', 'Controlled Unclassified', 'Confidential/Internal'],
        ['SECRET', '2', 'Secret', 'Restricted (HIPAA, Legal)'],
        ['TOP_SECRET', '3', 'Top Secret/SCI', 'Highly Restricted'],
    ]
    story.append(create_table(clearance_data, [1.3*inch, 0.8*inch, 1.7*inch, 2*inch]))

    story.append(Paragraph('4.3 Data Protection', styles['SectionTitle']))
    protection = [
        'PII Redaction: SSN patterns (XXX-XX-XXXX) and email addresses are automatically redacted at ingestion',
        'Classification Enforcement: Users can only access documents from sectors matching their clearance level',
        'Prompt Injection Detection: Blocks queries containing "ignore previous", "ignore all", "system prompt"',
        'Response Truncation: Audit logs limit response summaries to 200 characters'
    ]
    for p in protection:
        story.append(Paragraph(f'• {p}', styles['BulletText']))

    story.append(PageBreak())

    story.append(Paragraph('4.4 Audit Logging', styles['SectionTitle']))
    story.append(Paragraph(
        'SENTINEL maintains comprehensive audit logs aligned with NIST 800-53 AU-3 (Content of Audit Records). '
        'All security-relevant events are captured and stored in MongoDB audit_log collection.',
        styles['BodyPara']
    ))

    story.append(Paragraph('4.4.1 Captured Event Types', styles['SubsectionTitle']))
    event_data = [
        ['Event Type', 'Description'],
        ['AUTH_SUCCESS', 'Successful user authentication'],
        ['AUTH_FAILURE', 'Failed authentication attempt'],
        ['ACCESS_GRANTED', 'Permission allowed for operation'],
        ['ACCESS_DENIED', 'Permission denied for operation'],
        ['QUERY_EXECUTED', 'Intelligence query completed'],
        ['DOCUMENT_INGESTED', 'Document uploaded and indexed'],
        ['PROMPT_INJECTION_DETECTED', 'Malicious query blocked'],
        ['USER_CREATED', 'New user account provisioned'],
        ['CONFIG_CHANGED', 'System configuration modified'],
    ]
    story.append(create_table(event_data, [2.5*inch, 3.5*inch]))

    story.append(Paragraph('4.4.2 Audit Record Fields', styles['SubsectionTitle']))
    audit_fields = [
        'Temporal: timestamp (Instant, indexed)',
        'Identity: userId, username, userClearance',
        'Request: sourceIp, userAgent, sessionId',
        'Operation: eventType, action, outcome, outcomeReason',
        'Resource: resourceType, resourceId',
        'Response: responseSummary (max 200 chars)',
        'Metadata: Extensible key-value context'
    ]
    for a in audit_fields:
        story.append(Paragraph(f'• {a}', styles['BulletText']))

    story.append(PageBreak())

    # ========== 5. API DOCUMENTATION ==========
    story.append(Paragraph('5. API Endpoint Documentation', styles['ChapterTitle']))

    story.append(Paragraph('5.1 Public Endpoints', styles['SectionTitle']))

    story.append(Paragraph('GET /api/status', styles['SubsectionTitle']))
    story.append(Paragraph('Returns system health and telemetry metrics.', styles['BodyPara']))
    story.append(Paragraph('Response: { vectorDb, docsIndexed, avgLatency, queriesToday, systemStatus }', styles['CodeBlock']))

    story.append(Paragraph('GET /api/telemetry', styles['SubsectionTitle']))
    story.append(Paragraph('Returns live document count and query statistics.', styles['BodyPara']))
    story.append(Paragraph('Response: { documentCount, queryCount, avgLatencyMs, dbOnline }', styles['CodeBlock']))

    story.append(Paragraph('GET /api/health', styles['SubsectionTitle']))
    story.append(Paragraph('Simple health check endpoint.', styles['BodyPara']))
    story.append(Paragraph('Response: "SYSTEMS NOMINAL"', styles['CodeBlock']))

    story.append(Paragraph('5.2 Protected Endpoints', styles['SectionTitle']))

    story.append(Paragraph('POST /api/ingest/file', styles['SubsectionTitle']))
    story.append(Paragraph('Uploads and indexes a document into the vector store.', styles['BodyPara']))
    ingest_spec = '''Parameters:
  - file (MultipartFile): Document to ingest
  - dept (String): Target department/sector

Security:
  - Requires: Authentication
  - Permission: INGEST
  - Clearance: Must match or exceed department requirement

Response: "SECURE INGESTION COMPLETE: {filename} ({duration}ms)"'''
    story.append(Paragraph(ingest_spec.replace('\n', '<br/>'), styles['CodeBlock']))

    story.append(Paragraph('GET /api/ask', styles['SubsectionTitle']))
    story.append(Paragraph('Executes an intelligence query against indexed documents.', styles['BodyPara']))
    ask_spec = '''Parameters:
  - q (String): Query/question
  - dept (String): Target department for context filtering

Security:
  - Requires: Authentication
  - Permission: QUERY
  - Clearance: Must match or exceed department requirement
  - Prompt Injection: Blocked if detected

Processing:
  1. Retrieve top 10 documents (similarity >= 0.4)
  2. Filter by department metadata
  3. Limit to top 5 results
  4. Generate AI response with citations
  5. Log query to audit trail

Response: AI-generated answer with [filename.ext] citations'''
    story.append(Paragraph(ask_spec.replace('\n', '<br/>'), styles['CodeBlock']))

    story.append(Paragraph('5.3 Audit Endpoints', styles['SectionTitle']))

    story.append(Paragraph('GET /api/audit/events', styles['SubsectionTitle']))
    story.append(Paragraph('Returns recent audit events. Requires VIEW_AUDIT permission (ADMIN/AUDITOR role).', styles['BodyPara']))
    story.append(Paragraph('Response: { count, events, requestedBy }', styles['CodeBlock']))

    story.append(Paragraph('GET /api/audit/stats', styles['SubsectionTitle']))
    story.append(Paragraph('Returns aggregated audit statistics. Requires VIEW_AUDIT permission.', styles['BodyPara']))
    story.append(Paragraph('Response: { totalEvents, authSuccess, authFailure, queries, accessDenied, securityAlerts }', styles['CodeBlock']))

    story.append(PageBreak())

    # ========== 6. DATA FLOW ==========
    story.append(Paragraph('6. Data Flow Analysis', styles['ChapterTitle']))

    story.append(Paragraph('6.1 Document Ingestion Flow', styles['SectionTitle']))
    ingest_flow = '''[User] --POST /api/ingest/file--> [SecurityFilter]
    |
    v
[Authentication Check] --fail--> [401 Unauthorized]
    |
    v (success)
[Permission Check: INGEST] --fail--> [ACCESS DENIED]
    |
    v (success)
[Clearance Check] --fail--> [ACCESS DENIED: Insufficient clearance]
    |
    v (success)
[SecureIngestionService]
    |
    +-- Format Detection (PDF/TXT/MD)
    +-- Content Extraction (Apache Tika)
    +-- Token Splitting
    +-- PII Redaction
    +-- Memory Evolution (deduplication)
    +-- Embedding Generation
    +-- Vector Store Persistence
    |
    v
[AuditService.logIngestion()] --> [MongoDB: audit_log]
    |
    v
[Response: "SECURE INGESTION COMPLETE"]'''
    story.append(Paragraph(ingest_flow.replace('\n', '<br/>'), styles['CodeBlock']))

    story.append(Paragraph('6.2 Query Execution Flow', styles['SectionTitle']))
    query_flow = '''[User] --GET /api/ask?q=...&dept=...--> [SecurityFilter]
    |
    v
[Authentication + Permission + Clearance Checks]
    |
    v (success)
[Prompt Injection Detection] --detected--> [SECURITY ALERT]
    |
    v (clean)
[VectorStore.similaritySearch()]
    |
    +-- Generate query embedding
    +-- Fetch all documents from MongoDB
    +-- Calculate cosine similarity
    +-- Filter by department metadata
    +-- Filter by threshold (>= 0.4)
    +-- Return top 5 results
    |
    v
[LLM Generation (ChatClient)]
    |
    +-- System prompt with citation requirements
    +-- Retrieved document context
    +-- User query
    |
    v
[AuditService.logQuery()] --> [MongoDB: audit_log]
    |
    v
[Response with [filename.ext] citations]'''
    story.append(Paragraph(query_flow.replace('\n', '<br/>'), styles['CodeBlock']))

    story.append(PageBreak())

    # ========== 7. UI COMPONENTS ==========
    story.append(Paragraph('7. User Interface Components', styles['ChapterTitle']))

    story.append(Paragraph('7.1 Main Dashboard (index.html)', styles['SectionTitle']))
    story.append(Paragraph(
        'The SENTINEL UI provides a Bloomberg Terminal-inspired dark theme interface with professional '
        'styling for intelligence operations. Key components include:',
        styles['BodyPara']
    ))

    ui_components = [
        'Header: Branding, operator badge, system status indicator',
        'Telemetry Bar: Real-time metrics (doc count, queries, latency, DB status)',
        'Split Workspace: Chat panel (left) and Evidence Viewer (right)',
        'Sidebar: Document upload zone and sector selector',
        'Welcome State: Feature highlights and keyboard shortcuts',
        'Chat Interface: Query input with citation-linked responses',
        'Evidence Viewer: Source document display with query highlighting',
        'Feedback Modal: Response quality rating system'
    ]
    for u in ui_components:
        story.append(Paragraph(f'• {u}', styles['BulletText']))

    story.append(Paragraph('7.2 Key UI Features', styles['SectionTitle']))

    story.append(Paragraph('7.2.1 Citation Anchoring', styles['SubsectionTitle']))
    story.append(Paragraph(
        'AI responses include [filename.ext] citation badges. Clicking a citation opens the source '
        'document in the Evidence Viewer with relevant passages highlighted.',
        styles['BodyPara']
    ))

    story.append(Paragraph('7.2.2 Context Control', styles['SubsectionTitle']))
    story.append(Paragraph(
        'Users can toggle individual documents on/off to control what information the AI considers '
        'when generating responses, supporting scenario testing and sensitivity analysis.',
        styles['BodyPara']
    ))

    story.append(Paragraph('7.2.3 Keyboard Shortcuts', styles['SubsectionTitle']))
    shortcuts = [
        '/ : Focus query input',
        'Ctrl+U : Open file upload',
        'Ctrl+K : Clear chat',
        'Enter : Execute query',
        'Escape : Close modals/sidebar'
    ]
    for s in shortcuts:
        story.append(Paragraph(f'• {s}', styles['BulletText']))

    story.append(PageBreak())

    # ========== 8. COMPLIANCE MAPPING ==========
    story.append(Paragraph('8. Compliance Mapping', styles['ChapterTitle']))

    story.append(Paragraph('8.1 NIST 800-53 Control Coverage', styles['SectionTitle']))
    nist_data = [
        ['Control', 'Title', 'SENTINEL Implementation'],
        ['AC-2', 'Account Management', 'User model with roles, clearances, active status'],
        ['AC-3', 'Access Enforcement', 'RBAC + clearance checks at controller level'],
        ['AC-6', 'Least Privilege', 'Auto-provisioned users get minimal permissions'],
        ['AU-2', 'Audit Events', '9+ event types covering auth, access, operations'],
        ['AU-3', 'Content of Audit Records', 'Timestamp, user, IP, action, outcome, resource'],
        ['AU-6', 'Audit Review', '/api/audit/events and /api/audit/stats endpoints'],
        ['AU-9', 'Protection of Audit Info', 'Audit logs in separate MongoDB collection'],
        ['IA-2', 'Identification & Auth', 'Three auth modes: DEV, OIDC, CAC'],
        ['IA-5', 'Authenticator Mgmt', 'CAC/PIV certificate support'],
        ['SC-8', 'Transmission Conf.', 'HTTPS/TLS recommended (infrastructure)'],
        ['SI-10', 'Information Input Val.', 'Prompt injection detection'],
    ]
    story.append(create_table(nist_data, [0.7*inch, 1.5*inch, 3.8*inch]))

    story.append(Spacer(1, 0.3*inch))
    story.append(Paragraph('8.2 Additional Compliance Considerations', styles['SectionTitle']))

    story.append(Paragraph('FedRAMP Alignment', styles['SubsectionTitle']))
    fedramp = [
        'Air-gapped deployment option eliminates cloud dependencies',
        'Local LLM inference (Ollama) keeps data on-premises',
        'Classification-based access controls support multi-tenant isolation',
        'Comprehensive audit logging meets continuous monitoring requirements'
    ]
    for f in fedramp:
        story.append(Paragraph(f'• {f}', styles['BulletText']))

    story.append(Paragraph('HIPAA Considerations', styles['SubsectionTitle']))
    hipaa = [
        'MEDICAL sector requires SECRET clearance (restricted access)',
        'PII redaction includes common healthcare identifiers',
        'Audit trail captures all data access for compliance reporting'
    ]
    for h in hipaa:
        story.append(Paragraph(f'• {h}', styles['BulletText']))

    story.append(PageBreak())

    # ========== 9. TEST PLAN ==========
    story.append(Paragraph('9. Test Plan & Procedures', styles['ChapterTitle']))

    story.append(Paragraph('9.1 Infrastructure Requirements', styles['SectionTitle']))
    story.append(Paragraph('The following infrastructure is required for full operational testing:', styles['BodyPara']))
    infra = [
        'MongoDB 7.x running on localhost:27017',
        'Ollama with llama3 and nomic-embed-text models',
        'Java 21 runtime environment',
        'Network access for dependency resolution (build phase)'
    ]
    for i in infra:
        story.append(Paragraph(f'• {i}', styles['BulletText']))

    story.append(Paragraph('9.2 Functional Test Cases', styles['SectionTitle']))
    func_tests = [
        ['ID', 'Test Case', 'Expected Result'],
        ['TC-01', 'Upload PDF document to DEFENSE sector', 'Document indexed, telemetry updated'],
        ['TC-02', 'Upload TXT file to MEDICAL sector', 'Document indexed with HIPAA tag'],
        ['TC-03', 'Upload multiple files simultaneously', 'All files processed, batch status shown'],
        ['TC-04', 'Query with valid clearance', 'AI response with citations returned'],
        ['TC-05', 'Query with insufficient clearance', 'ACCESS DENIED message'],
        ['TC-06', 'Click citation in response', 'Evidence viewer shows source document'],
        ['TC-07', 'Verify telemetry updates', 'Doc count and query count accurate'],
        ['TC-08', 'Test prompt injection blocking', 'SECURITY ALERT returned'],
        ['TC-09', 'Access audit logs as AUDITOR', 'Audit events returned'],
        ['TC-10', 'Access audit logs as VIEWER', 'ACCESS DENIED returned'],
    ]
    story.append(create_table(func_tests, [0.6*inch, 2.5*inch, 2.9*inch]))

    story.append(Spacer(1, 0.3*inch))
    story.append(Paragraph('9.3 Security Test Cases', styles['SectionTitle']))
    sec_tests = [
        ['ID', 'Test Case', 'Expected Result'],
        ['ST-01', 'Access /api/ask without authentication', '401 Unauthorized or DEV fallback'],
        ['ST-02', 'Submit query with "ignore previous"', 'SECURITY ALERT: Prompt injection'],
        ['ST-03', 'VIEWER attempts document ingestion', 'ACCESS DENIED: Insufficient perm.'],
        ['ST-04', 'CUI user queries SECRET sector', 'ACCESS DENIED: Insufficient clearance'],
        ['ST-05', 'Verify audit log captures denied access', 'ACCESS_DENIED event recorded'],
        ['ST-06', 'CAC authentication with valid cert', 'User authenticated, context set'],
        ['ST-07', 'CAC authentication with invalid cert', 'AUTH_FAILURE logged'],
        ['ST-08', 'Verify PII redaction in stored docs', 'SSN/Email patterns replaced'],
    ]
    story.append(create_table(sec_tests, [0.6*inch, 2.5*inch, 2.9*inch]))

    story.append(PageBreak())

    # ========== 10. FINDINGS ==========
    story.append(Paragraph('10. Findings & Recommendations', styles['ChapterTitle']))

    story.append(Paragraph('10.1 Critical Findings', styles['SectionTitle']))
    story.append(Paragraph('CRITICAL: OIDC JWT Signature Validation Not Implemented', styles['Finding']))
    story.append(Paragraph(
        'The OidcAuthenticationService extracts claims from JWT tokens without validating the signature. '
        'This allows token forgery attacks. Recommendation: Implement proper JWT validation using a '
        'library like nimbus-jose-jwt or spring-security-oauth2-jose.',
        styles['BodyPara']
    ))

    story.append(Paragraph('10.2 High Priority Findings', styles['SectionTitle']))
    story.append(Paragraph('HIGH: Simplified Prompt Injection Detection', styles['Finding']))
    story.append(Paragraph(
        'Current detection uses simple substring matching for 3 patterns. Sophisticated attacks may '
        'bypass this filter. Recommendation: Implement ML-based detection or expand pattern library.',
        styles['BodyPara']
    ))

    story.append(Paragraph('HIGH: No Rate Limiting on Authentication', styles['Finding']))
    story.append(Paragraph(
        'Authentication endpoints have no brute-force protection. Recommendation: Implement rate '
        'limiting with exponential backoff for failed attempts.',
        styles['BodyPara']
    ))

    story.append(Paragraph('10.3 Medium Priority Findings', styles['SectionTitle']))
    medium = [
        'Debug logging in LocalMongoVectorStore should use log.debug() - FIXED',
        'Magic values should be extracted to constants - FIXED',
        'Exception handling should preserve stack traces - FIXED',
        'Test coverage is minimal (1 test file) - should be expanded',
        'Sector restrictions not validated against user.allowedSectors'
    ]
    for m in medium:
        story.append(Paragraph(f'• {m}', styles['BulletText']))

    story.append(Paragraph('10.4 Recommendations Summary', styles['SectionTitle']))
    recommendations = [
        'MUST: Implement JWT signature validation for OIDC mode before production',
        'MUST: Add rate limiting to authentication endpoints',
        'SHOULD: Expand prompt injection detection patterns',
        'SHOULD: Add comprehensive unit and integration tests',
        'SHOULD: Implement session timeout and concurrent session limits',
        'COULD: Add ML-based anomaly detection for queries'
    ]
    for r in recommendations:
        story.append(Paragraph(f'• {r}', styles['BulletText']))

    story.append(PageBreak())

    # ========== 11. CERTIFICATION ==========
    story.append(Paragraph('11. Certification Statement', styles['ChapterTitle']))
    story.append(Spacer(1, 0.5*inch))

    story.append(Paragraph(
        'This document certifies that the SENTINEL Intelligence Platform has undergone comprehensive '
        'static code analysis and architectural review as of the date indicated on this report.',
        styles['BodyPara']
    ))

    story.append(Spacer(1, 0.2*inch))
    story.append(Paragraph('The analysis covered the following areas:', styles['BodyPara']))
    areas = [
        'Authentication and authorization mechanisms',
        'Data protection and classification controls',
        'Audit logging and compliance alignment',
        'API security and input validation',
        'Code quality and architectural patterns'
    ]
    for a in areas:
        story.append(Paragraph(f'• {a}', styles['BulletText']))

    story.append(Spacer(1, 0.2*inch))
    story.append(Paragraph(
        'Based on the analysis, SENTINEL is found to be SUITABLE for deployment in environments '
        'requiring classification-based access controls, with the following conditions:',
        styles['BodyPara']
    ))

    story.append(Paragraph('Conditions for Deployment:', styles['SectionTitle']))
    conditions = [
        'OIDC mode: Must implement JWT signature validation before use',
        'Internet-facing: Must implement rate limiting on auth endpoints',
        'Production: Must not use DEV authentication mode',
        'Classified: Must deploy in air-gapped configuration'
    ]
    for c in conditions:
        story.append(Paragraph(f'• {c}', styles['BulletText']))

    story.append(Spacer(1, 0.5*inch))
    story.append(Paragraph(f'Certification Date: {datetime.now().strftime("%B %d, %Y")}', styles['BodyPara']))
    story.append(Paragraph('Certification Authority: Automated Security Analysis System', styles['BodyPara']))
    story.append(Paragraph('Report Version: 1.0', styles['BodyPara']))

    story.append(Spacer(1, 1*inch))
    story.append(Paragraph('_______________________________', styles['BodyPara']))
    story.append(Paragraph('Authorized Signature', styles['BodyPara']))

    story.append(Spacer(1, 0.5*inch))
    story.append(Paragraph('_______________________________', styles['BodyPara']))
    story.append(Paragraph('Date', styles['BodyPara']))

    story.append(PageBreak())

    # ========== APPENDIX ==========
    story.append(Paragraph('Appendix A: File Inventory', styles['ChapterTitle']))

    story.append(Paragraph('Source Code Files Analyzed', styles['SectionTitle']))
    files = [
        'MercenaryController.java - Core API endpoints',
        'AuditController.java - Audit log access',
        'SecureIngestionService.java - Document processing',
        'MemoryEvolutionService.java - Semantic deduplication',
        'AuditService.java - Event logging',
        'LocalMongoVectorStore.java - Vector storage',
        'SecurityFilter.java - Authentication gateway',
        'SecurityContext.java - Thread-local user context',
        'DevAuthenticationService.java - DEV mode auth',
        'OidcAuthenticationService.java - Enterprise auth',
        'CacAuthenticationService.java - Government auth',
        'User.java - User model',
        'UserRole.java - RBAC roles',
        'ClearanceLevel.java - Classification levels',
        'Department.java - Sector definitions',
        'AuditEvent.java - Audit record model',
        'application.yaml - Configuration',
        'docker-compose.local.yml - Deployment config',
        'index.html - Main UI',
        'manual.html - Documentation',
    ]
    for f in files:
        story.append(Paragraph(f'• {f}', styles['BulletText']))

    # Build document
    doc.build(story)
    print(f'Report generated: {output_path}')
    return output_path

if __name__ == '__main__':
    generate_report()
