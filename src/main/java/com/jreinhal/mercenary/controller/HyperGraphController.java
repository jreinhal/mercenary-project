package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.config.SectorConfig;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory;
import com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory.HGNode;
import com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory.HGEdge;
import com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory.HGStats;
import com.jreinhal.mercenary.rag.hgmem.EntityExtractor;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.util.LogSanitizer;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for HyperGraph Memory visualization.
 * Provides entity network data for the Entity Explorer UI.
 *
 * SECURITY: All endpoints enforce sector isolation and clearance checks.
 */
@RestController
@RequestMapping("/api/graph")
public class HyperGraphController {

    private static final Logger log = LoggerFactory.getLogger(HyperGraphController.class);
    private static final String NODES_COLLECTION = "hypergraph_nodes";
    private static final String EDGES_COLLECTION = "hypergraph_edges";
    private static final Set<String> VALID_DEPARTMENTS = Set.of(
        "GOVERNMENT", "MEDICAL", "FINANCE", "ACADEMIC", "ENTERPRISE"
    );
    private static final int MAX_NODES = 200;
    private static final int MAX_NEIGHBORS = 50;

    private final HyperGraphMemory hyperGraphMemory;
    private final MongoTemplate mongoTemplate;
    private final AuditService auditService;
    private final SectorConfig sectorConfig;

    public HyperGraphController(
            HyperGraphMemory hyperGraphMemory,
            MongoTemplate mongoTemplate,
            AuditService auditService,
            SectorConfig sectorConfig) {
        this.hyperGraphMemory = hyperGraphMemory;
        this.mongoTemplate = mongoTemplate;
        this.auditService = auditService;
        this.sectorConfig = sectorConfig;
    }

    /**
     * Get all entity nodes for a department (for graph visualization).
     * Returns nodes with their types and reference counts.
     *
     * GET /api/graph/entities?dept=ENTERPRISE&limit=100
     */
    @GetMapping("/entities")
    public EntityListResponse getEntities(
            @RequestParam("dept") String deptParam,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "type", required = false) String nodeType) {

        // Security: Validate department parameter
        String dept = deptParam.toUpperCase();
        if (!VALID_DEPARTMENTS.contains(dept)) {
            log.warn("SECURITY: Invalid department in graph/entities request: {}", deptParam);
            return new EntityListResponse(List.of(), 0, "ERROR: Invalid sector.");
        }

        // Security: Authenticate user
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            auditService.logAccessDenied(null, "/api/graph/entities", "Unauthenticated access attempt", null);
            return new EntityListResponse(List.of(), 0, "ACCESS DENIED: Authentication required.");
        }

        // Security: Check QUERY permission
        if (!user.hasPermission(UserRole.Permission.QUERY)) {
            auditService.logAccessDenied(user, "/api/graph/entities", "Missing QUERY permission", null);
            return new EntityListResponse(List.of(), 0, "ACCESS DENIED: Insufficient permissions.");
        }

        // Security: Check sector access
        Department department = Department.valueOf(dept);
        if (sectorConfig.requiresElevatedClearance(department) &&
            !user.canAccessClassification(department.getRequiredClearance())) {
            auditService.logAccessDenied(user, "/api/graph/entities", "Insufficient clearance for " + dept, null);
            return new EntityListResponse(List.of(), 0, "ACCESS DENIED: Insufficient clearance for " + dept);
        }

        if (!user.canAccessSector(department)) {
            auditService.logAccessDenied(user, "/api/graph/entities", "Not authorized for sector " + dept, null);
            return new EntityListResponse(List.of(), 0, "ACCESS DENIED: Unauthorized sector access.");
        }

        // Check if HGMem is enabled
        if (!hyperGraphMemory.isIndexingEnabled()) {
            return new EntityListResponse(List.of(), 0, "HyperGraph Memory is disabled.");
        }

        try {
            String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
            // Build query with sector filter
            Criteria criteria = Criteria.where("department").is(dept)
                    .and("workspaceId").is(workspaceId)
                    .and("type").is(HGNode.NodeType.ENTITY.name());

            if (nodeType != null && !nodeType.isBlank()) {
                criteria = criteria.and("entityType").is(nodeType.toUpperCase());
            }

            Query query = new Query(criteria)
                    .limit(Math.min(limit, MAX_NODES));

            List<HGNode> nodes = mongoTemplate.find(query, HGNode.class, NODES_COLLECTION);

            List<EntityNodeDto> dtos = nodes.stream()
                    .map(this::toEntityDto)
                    .collect(Collectors.toList());

            log.info("Graph API: Returned {} entities for sector {} (user={})",
                    dtos.size(), dept, user.getDisplayName());

            return new EntityListResponse(dtos, dtos.size(), null);

        } catch (Exception e) {
            log.error("Error fetching graph entities: {}", e.getMessage(), e);
            return new EntityListResponse(List.of(), 0, "Error retrieving graph data.");
        }
    }

    /**
     * Get neighbors of a specific node (for expanding graph on click).
     * Returns connected nodes via hyperedges.
     *
     * GET /api/graph/neighbors?nodeId=xxx&dept=ENTERPRISE
     */
    @GetMapping("/neighbors")
    public NeighborResponse getNeighbors(
            @RequestParam("nodeId") String nodeId,
            @RequestParam("dept") String deptParam) {

        // Security: Validate department parameter
        String dept = deptParam.toUpperCase();
        if (!VALID_DEPARTMENTS.contains(dept)) {
            log.warn("SECURITY: Invalid department in graph/neighbors request: {}", deptParam);
            return new NeighborResponse(List.of(), List.of(), "ERROR: Invalid sector.");
        }

        // Security: Authenticate user
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            auditService.logAccessDenied(null, "/api/graph/neighbors", "Unauthenticated access attempt", null);
            return new NeighborResponse(List.of(), List.of(), "ACCESS DENIED: Authentication required.");
        }

        // Security: Check QUERY permission
        if (!user.hasPermission(UserRole.Permission.QUERY)) {
            auditService.logAccessDenied(user, "/api/graph/neighbors", "Missing QUERY permission", null);
            return new NeighborResponse(List.of(), List.of(), "ACCESS DENIED: Insufficient permissions.");
        }

        // Security: Check sector access
        Department department = Department.valueOf(dept);
        if (sectorConfig.requiresElevatedClearance(department) &&
            !user.canAccessClassification(department.getRequiredClearance())) {
            auditService.logAccessDenied(user, "/api/graph/neighbors", "Insufficient clearance for " + dept, null);
            return new NeighborResponse(List.of(), List.of(), "ACCESS DENIED: Insufficient clearance for " + dept);
        }

        if (!user.canAccessSector(department)) {
            auditService.logAccessDenied(user, "/api/graph/neighbors", "Not authorized for sector " + dept, null);
            return new NeighborResponse(List.of(), List.of(), "ACCESS DENIED: Unauthorized sector access.");
        }

        // Check if HGMem is enabled
        if (!hyperGraphMemory.isIndexingEnabled()) {
            return new NeighborResponse(List.of(), List.of(), "HyperGraph Memory is disabled.");
        }

        try {
            String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
            // First verify the source node belongs to the requested department
            HGNode sourceNode = mongoTemplate.findById(nodeId, HGNode.class, NODES_COLLECTION);
            if (sourceNode == null) {
                return new NeighborResponse(List.of(), List.of(), "Node not found.");
            }

            // SECURITY: Enforce sector boundary - node must belong to requested department
            if (!dept.equals(sourceNode.getDepartment())) {
                log.warn("SECURITY: Cross-sector node access attempt: user={}, requested={}, node_dept={}",
                        user.getDisplayName(), dept, sourceNode.getDepartment());
                auditService.logAccessDenied(user, "/api/graph/neighbors",
                        "Cross-sector access blocked: " + sourceNode.getDepartment(), null);
                return new NeighborResponse(List.of(), List.of(), "ACCESS DENIED: Node belongs to different sector.");
            }
            if (sourceNode.getWorkspaceId() != null && !sourceNode.getWorkspaceId().equalsIgnoreCase(workspaceId)) {
                log.warn("SECURITY: Cross-workspace node access attempt: user={}, requested={}, node_workspace={}",
                        user.getDisplayName(), workspaceId, sourceNode.getWorkspaceId());
                auditService.logAccessDenied(user, "/api/graph/neighbors",
                        "Cross-workspace access blocked: " + sourceNode.getWorkspaceId(), null);
                return new NeighborResponse(List.of(), List.of(), "ACCESS DENIED: Node belongs to different workspace.");
            }

            // SECURITY: Find edges containing this node, filtered by department
            Query edgeQuery = new Query(Criteria.where("nodeIds").is(nodeId)
                    .and("department").is(dept)
                    .and("workspaceId").is(workspaceId));
            List<HGEdge> edges = mongoTemplate.find(edgeQuery, HGEdge.class, EDGES_COLLECTION);

            // Collect neighbor node IDs
            Set<String> neighborIds = new HashSet<>();
            List<EdgeDto> edgeDtos = new ArrayList<>();

            for (HGEdge edge : edges) {
                for (String connectedId : edge.getNodeIds()) {
                    if (!connectedId.equals(nodeId)) {
                        neighborIds.add(connectedId);
                    }
                }
                edgeDtos.add(toEdgeDto(edge));
            }

            // Fetch neighbor nodes (limited)
            List<EntityNodeDto> neighborDtos = new ArrayList<>();
            int count = 0;
            for (String neighborId : neighborIds) {
                if (count >= MAX_NEIGHBORS) break;

                HGNode neighbor = mongoTemplate.findById(neighborId, HGNode.class, NODES_COLLECTION);
                if (neighbor != null) {
                    // SECURITY: Double-check department on each neighbor
                    if (dept.equals(neighbor.getDepartment()) && (neighbor.getWorkspaceId() == null || neighbor.getWorkspaceId().equalsIgnoreCase(workspaceId))) {
                        neighborDtos.add(toEntityDto(neighbor));
                        count++;
                    } else {
                        log.debug("Filtered out cross-sector neighbor: {}", neighborId);
                    }
                }
            }

            log.info("Graph API: Returned {} neighbors for node {} (user={})",
                    neighborDtos.size(), nodeId, user.getDisplayName());

            return new NeighborResponse(neighborDtos, edgeDtos, null);

        } catch (Exception e) {
            log.error("Error fetching neighbors: {}", e.getMessage(), e);
            return new NeighborResponse(List.of(), List.of(), "Error retrieving neighbor data.");
        }
    }

    /**
     * Search for entities by name/value.
     *
     * GET /api/graph/search?q=John&dept=ENTERPRISE
     */
    @GetMapping("/search")
    public EntityListResponse searchEntities(
            @RequestParam("q") String query,
            @RequestParam("dept") String deptParam,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {

        // Security: Validate department parameter
        String dept = deptParam.toUpperCase();
        if (!VALID_DEPARTMENTS.contains(dept)) {
            log.warn("SECURITY: Invalid department in graph/search request: {}", deptParam);
            return new EntityListResponse(List.of(), 0, "ERROR: Invalid sector.");
        }

        // Security: Authenticate user
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            auditService.logAccessDenied(null, "/api/graph/search", "Unauthenticated access attempt", null);
            return new EntityListResponse(List.of(), 0, "ACCESS DENIED: Authentication required.");
        }

        // Security: Check QUERY permission
        if (!user.hasPermission(UserRole.Permission.QUERY)) {
            auditService.logAccessDenied(user, "/api/graph/search", "Missing QUERY permission", null);
            return new EntityListResponse(List.of(), 0, "ACCESS DENIED: Insufficient permissions.");
        }

        // Security: Check sector access
        Department department = Department.valueOf(dept);
        if (sectorConfig.requiresElevatedClearance(department) &&
            !user.canAccessClassification(department.getRequiredClearance())) {
            auditService.logAccessDenied(user, "/api/graph/search", "Insufficient clearance for " + dept, null);
            return new EntityListResponse(List.of(), 0, "ACCESS DENIED: Insufficient clearance for " + dept);
        }

        if (!user.canAccessSector(department)) {
            auditService.logAccessDenied(user, "/api/graph/search", "Not authorized for sector " + dept, null);
            return new EntityListResponse(List.of(), 0, "ACCESS DENIED: Unauthorized sector access.");
        }

        // Check if HGMem is enabled
        if (!hyperGraphMemory.isIndexingEnabled()) {
            return new EntityListResponse(List.of(), 0, "HyperGraph Memory is disabled.");
        }

        // Sanitize search query to prevent regex injection
        String sanitized = escapeRegex(query);

        try {
            Query mongoQuery = new Query(Criteria.where("department").is(dept)
                    .and("workspaceId").is(WorkspaceContext.getCurrentWorkspaceId())
                    .and("type").is(HGNode.NodeType.ENTITY.name())
                    .and("value").regex(sanitized, "i"))
                    .limit(Math.min(limit, MAX_NODES));

            List<HGNode> nodes = mongoTemplate.find(mongoQuery, HGNode.class, NODES_COLLECTION);

            List<EntityNodeDto> dtos = nodes.stream()
                    .map(this::toEntityDto)
                    .collect(Collectors.toList());

            log.info("Graph API: Search for '{}' returned {} entities in sector {} (user={})",
                    LogSanitizer.querySummary(query), dtos.size(), dept, user.getDisplayName());

            return new EntityListResponse(dtos, dtos.size(), null);

        } catch (Exception e) {
            log.error("Error searching entities: {}", e.getMessage(), e);
            return new EntityListResponse(List.of(), 0, "Error searching entities.");
        }
    }

    /**
     * Get graph statistics for a department.
     *
     * GET /api/graph/stats?dept=ENTERPRISE
     */
    @GetMapping("/stats")
    public GraphStatsResponse getStats(@RequestParam("dept") String deptParam) {

        // Security: Validate department parameter
        String dept = deptParam.toUpperCase();
        if (!VALID_DEPARTMENTS.contains(dept)) {
            log.warn("SECURITY: Invalid department in graph/stats request: {}", deptParam);
            return new GraphStatsResponse(0, 0, 0, 0, false, "ERROR: Invalid sector.");
        }

        // Security: Authenticate user
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            auditService.logAccessDenied(null, "/api/graph/stats", "Unauthenticated access attempt", null);
            return new GraphStatsResponse(0, 0, 0, 0, false, "ACCESS DENIED: Authentication required.");
        }

        // Security: Check QUERY permission
        if (!user.hasPermission(UserRole.Permission.QUERY)) {
            auditService.logAccessDenied(user, "/api/graph/stats", "Missing QUERY permission", null);
            return new GraphStatsResponse(0, 0, 0, 0, false, "ACCESS DENIED: Insufficient permissions.");
        }

        // Security: Check sector access
        Department department = Department.valueOf(dept);
        if (sectorConfig.requiresElevatedClearance(department) &&
            !user.canAccessClassification(department.getRequiredClearance())) {
            auditService.logAccessDenied(user, "/api/graph/stats", "Insufficient clearance for " + dept, null);
            return new GraphStatsResponse(0, 0, 0, 0, false, "ACCESS DENIED: Insufficient clearance for " + dept);
        }

        if (!user.canAccessSector(department)) {
            auditService.logAccessDenied(user, "/api/graph/stats", "Not authorized for sector " + dept, null);
            return new GraphStatsResponse(0, 0, 0, 0, false, "ACCESS DENIED: Unauthorized sector access.");
        }

        boolean enabled = hyperGraphMemory.isIndexingEnabled();
        if (!enabled) {
            return new GraphStatsResponse(0, 0, 0, 0, false, null);
        }

        try {
            String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
            // Count nodes by department
            long entityCount = mongoTemplate.count(
                    new Query(Criteria.where("department").is(dept)
                            .and("workspaceId").is(workspaceId)
                            .and("type").is(HGNode.NodeType.ENTITY.name())),
                    NODES_COLLECTION);

            long chunkCount = mongoTemplate.count(
                    new Query(Criteria.where("department").is(dept)
                            .and("workspaceId").is(workspaceId)
                            .and("type").is(HGNode.NodeType.CHUNK.name())),
                    NODES_COLLECTION);

            long edgeCount = mongoTemplate.count(
                    new Query(Criteria.where("department").is(dept).and("workspaceId").is(workspaceId)),
                    EDGES_COLLECTION);

            long totalNodes = entityCount + chunkCount;

            return new GraphStatsResponse(totalNodes, edgeCount, entityCount, chunkCount, true, null);

        } catch (Exception e) {
            log.error("Error fetching graph stats: {}", e.getMessage(), e);
            return new GraphStatsResponse(0, 0, 0, 0, enabled, "Error retrieving stats.");
        }
    }

    /**
     * Get edges for a department (for initial graph visualization).
     * Returns edges that connect the loaded entities.
     *
     * GET /api/graph/edges?dept=ENTERPRISE&limit=200
     */
    @GetMapping("/edges")
    public EdgeListResponse getEdges(
            @RequestParam("dept") String deptParam,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {

        // Security: Validate department parameter
        String dept = deptParam.toUpperCase();
        if (!VALID_DEPARTMENTS.contains(dept)) {
            log.warn("SECURITY: Invalid department in graph/edges request: {}", deptParam);
            return new EdgeListResponse(List.of(), 0, "ERROR: Invalid sector.");
        }

        // Security: Authenticate user
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            auditService.logAccessDenied(null, "/api/graph/edges", "Unauthenticated access attempt", null);
            return new EdgeListResponse(List.of(), 0, "ACCESS DENIED: Authentication required.");
        }

        // Security: Check QUERY permission
        if (!user.hasPermission(UserRole.Permission.QUERY)) {
            auditService.logAccessDenied(user, "/api/graph/edges", "Missing QUERY permission", null);
            return new EdgeListResponse(List.of(), 0, "ACCESS DENIED: Insufficient permissions.");
        }

        // Security: Check sector access
        Department department = Department.valueOf(dept);
        if (sectorConfig.requiresElevatedClearance(department) &&
            !user.canAccessClassification(department.getRequiredClearance())) {
            auditService.logAccessDenied(user, "/api/graph/edges", "Insufficient clearance for " + dept, null);
            return new EdgeListResponse(List.of(), 0, "ACCESS DENIED: Insufficient clearance for " + dept);
        }

        if (!user.canAccessSector(department)) {
            auditService.logAccessDenied(user, "/api/graph/edges", "Not authorized for sector " + dept, null);
            return new EdgeListResponse(List.of(), 0, "ACCESS DENIED: Unauthorized sector access.");
        }

        // Check if HGMem is enabled
        if (!hyperGraphMemory.isIndexingEnabled()) {
            return new EdgeListResponse(List.of(), 0, "HyperGraph Memory is disabled.");
        }

        try {
            // Fetch edges for department
            String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
            Query query = new Query(Criteria.where("department").is(dept).and("workspaceId").is(workspaceId))
                    .limit(Math.min(limit, 500));

            List<HGEdge> edges = mongoTemplate.find(query, HGEdge.class, EDGES_COLLECTION);

            List<EdgeDto> dtos = edges.stream()
                    .map(this::toEdgeDto)
                    .collect(Collectors.toList());

            log.info("Graph API: Returned {} edges for sector {} (user={})",
                    dtos.size(), dept, user.getDisplayName());

            return new EdgeListResponse(dtos, dtos.size(), null);

        } catch (Exception e) {
            log.error("Error fetching graph edges: {}", e.getMessage(), e);
            return new EdgeListResponse(List.of(), 0, "Error retrieving edge data.");
        }
    }

    // ==================== DTOs ====================

    private EntityNodeDto toEntityDto(HGNode node) {
        String resolvedType = node.getEntityType() != null
                ? node.getEntityType().name()
                : EntityExtractor.EntityType.REFERENCE.name();
        return new EntityNodeDto(
                node.getId(),
                node.getValue(),
                node.getType().name(),
                resolvedType,
                node.getReferenceCount(),
                node.getSourceDoc()
        );
    }

    private EdgeDto toEdgeDto(HGEdge edge) {
        return new EdgeDto(
                edge.getId(),
                edge.getNodeIds(),
                edge.getRelation(),
                edge.getWeight(),
                edge.getSourceDoc()
        );
    }

    private String escapeRegex(String text) {
        if (text == null) return "";
        return text.replaceAll("([\\\\^$.|?*+()\\[\\]{}])", "\\\\$1");
    }

    // ==================== Response Records ====================

    public record EntityListResponse(
            List<EntityNodeDto> entities,
            int total,
            String error
    ) {}

    public record NeighborResponse(
            List<EntityNodeDto> neighbors,
            List<EdgeDto> edges,
            String error
    ) {}

    public record GraphStatsResponse(
            long totalNodes,
            long totalEdges,
            long entityCount,
            long chunkCount,
            boolean enabled,
            String error
    ) {}

    public record EdgeListResponse(
            List<EdgeDto> edges,
            int total,
            String error
    ) {}

    public record EntityNodeDto(
            String id,
            String value,
            String type,
            String entityType,
            int referenceCount,
            String sourceDoc
    ) {}

    public record EdgeDto(
            String id,
            List<String> nodeIds,
            String relation,
            double weight,
            String sourceDoc
    ) {}
}
