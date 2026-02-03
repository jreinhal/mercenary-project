package com.jreinhal.mercenary.connectors;

import java.util.List;

public final class ConnectorCatalog {
    private static final List<ConnectorDefinition> DEFINITIONS = List.of(
        new ConnectorDefinition(
            "S3",
            "Amazon S3",
            "Cloud Storage",
            "Sync documents from an S3-compatible bucket for ingestion.",
            List.of(
                "SENTINEL_CONNECTORS_ENABLED",
                "SENTINEL_S3_ENABLED",
                "SENTINEL_S3_BUCKET",
                "SENTINEL_S3_PREFIX",
                "SENTINEL_S3_REGION",
                "SENTINEL_S3_ENDPOINT",
                "SENTINEL_S3_ACCESS_KEY",
                "SENTINEL_S3_SECRET_KEY",
                "SENTINEL_S3_MAX_FILES",
                "SENTINEL_S3_DEPT"
            ),
            false
        ),
        new ConnectorDefinition(
            "SharePoint",
            "Microsoft SharePoint",
            "Enterprise Content",
            "Sync documents from a SharePoint drive or folder via Microsoft Graph.",
            List.of(
                "SENTINEL_CONNECTORS_ENABLED",
                "SENTINEL_SHAREPOINT_ENABLED",
                "SENTINEL_SHAREPOINT_GRAPH",
                "SENTINEL_SHAREPOINT_DRIVE_ID",
                "SENTINEL_SHAREPOINT_FOLDER",
                "SENTINEL_SHAREPOINT_TOKEN",
                "SENTINEL_SHAREPOINT_MAX_FILES",
                "SENTINEL_SHAREPOINT_DEPT"
            ),
            false
        ),
        new ConnectorDefinition(
            "Confluence",
            "Atlassian Confluence",
            "Enterprise Content",
            "Sync knowledge base pages from Confluence spaces.",
            List.of(
                "SENTINEL_CONNECTORS_ENABLED",
                "SENTINEL_CONFLUENCE_ENABLED",
                "SENTINEL_CONFLUENCE_URL",
                "SENTINEL_CONFLUENCE_EMAIL",
                "SENTINEL_CONFLUENCE_TOKEN",
                "SENTINEL_CONFLUENCE_SPACE",
                "SENTINEL_CONFLUENCE_LIMIT",
                "SENTINEL_CONFLUENCE_PAGES",
                "SENTINEL_CONFLUENCE_DEPT"
            ),
            false
        ),
        new ConnectorDefinition(
            "Jira",
            "Atlassian Jira",
            "Work Management",
            "Sync Jira issues, tickets, and project knowledge for incident response.",
            List.of(
                "SENTINEL_CONNECTORS_ENABLED",
                "SENTINEL_JIRA_ENABLED",
                "SENTINEL_JIRA_URL",
                "SENTINEL_JIRA_EMAIL",
                "SENTINEL_JIRA_TOKEN",
                "SENTINEL_JIRA_PROJECTS",
                "SENTINEL_JIRA_LIMIT",
                "SENTINEL_JIRA_DEPT"
            ),
            false
        ),
        new ConnectorDefinition(
            "ServiceNow",
            "ServiceNow ITSM",
            "Service Management",
            "Sync incident, change, and knowledge base records from ServiceNow.",
            List.of(
                "SENTINEL_CONNECTORS_ENABLED",
                "SENTINEL_SNOW_ENABLED",
                "SENTINEL_SNOW_INSTANCE",
                "SENTINEL_SNOW_USERNAME",
                "SENTINEL_SNOW_PASSWORD",
                "SENTINEL_SNOW_TABLES",
                "SENTINEL_SNOW_LIMIT",
                "SENTINEL_SNOW_DEPT"
            ),
            false
        ),
        new ConnectorDefinition(
            "Box",
            "Box",
            "Enterprise Content",
            "Sync documents from Box folders for compliance and audit review.",
            List.of(
                "SENTINEL_CONNECTORS_ENABLED",
                "SENTINEL_BOX_ENABLED",
                "SENTINEL_BOX_CLIENT_ID",
                "SENTINEL_BOX_CLIENT_SECRET",
                "SENTINEL_BOX_ENTERPRISE_ID",
                "SENTINEL_BOX_FOLDER",
                "SENTINEL_BOX_LIMIT",
                "SENTINEL_BOX_DEPT"
            ),
            false
        ),
        new ConnectorDefinition(
            "GoogleDrive",
            "Google Drive",
            "Enterprise Content",
            "Sync documents from Google Drive shared folders.",
            List.of(
                "SENTINEL_CONNECTORS_ENABLED",
                "SENTINEL_GDRIVE_ENABLED",
                "SENTINEL_GDRIVE_CLIENT_ID",
                "SENTINEL_GDRIVE_CLIENT_SECRET",
                "SENTINEL_GDRIVE_REFRESH_TOKEN",
                "SENTINEL_GDRIVE_FOLDER",
                "SENTINEL_GDRIVE_LIMIT",
                "SENTINEL_GDRIVE_DEPT"
            ),
            false
        )
    );

    private ConnectorCatalog() {
    }

    public static List<ConnectorDefinition> definitions() {
        return DEFINITIONS;
    }

    public static ConnectorDefinition findById(String id) {
        if (id == null) {
            return null;
        }
        for (ConnectorDefinition def : DEFINITIONS) {
            if (def.id().equalsIgnoreCase(id) || def.name().equalsIgnoreCase(id)) {
                return def;
            }
        }
        return null;
    }

    public record ConnectorDefinition(
            String id,
            String name,
            String category,
            String description,
            List<String> configKeys,
            boolean supportsRegulated) {
    }
}
