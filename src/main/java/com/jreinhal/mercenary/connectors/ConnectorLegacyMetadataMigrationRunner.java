package com.jreinhal.mercenary.connectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ConnectorLegacyMetadataMigrationRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(ConnectorLegacyMetadataMigrationRunner.class);

    private final ConnectorLegacyMetadataMigrationService migrationService;

    @Value("${sentinel.connectors.legacy-metadata-migration.enabled:false}")
    private boolean enabled;

    @Value("${sentinel.connectors.legacy-metadata-migration.dry-run:true}")
    private boolean dryRun;

    @Value("${sentinel.connectors.legacy-metadata-migration.force:false}")
    private boolean force;

    public ConnectorLegacyMetadataMigrationRunner(ConnectorLegacyMetadataMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }
        if (!force && this.migrationService.isCompleted()) {
            log.info("Connector legacy metadata migration already completed; skipping.");
            return;
        }

        ConnectorLegacyMetadataMigrationService.MigrationResult result =
                this.migrationService.migrateLegacyMetadata(dryRun);
        if (dryRun) {
            log.info("Connector legacy metadata migration dry-run complete: {}", result);
            return;
        }
        if (result.sourceGroups() <= 0) {
            log.warn(
                    "Connector legacy metadata migration found no connector sync state records; " +
                            "skipping completion marker. Run connector sync at least once, then rerun migration."
            );
            return;
        }

        this.migrationService.markCompleted(result);
        log.info("Connector legacy metadata migration completed and marked: {}", result);
    }
}
