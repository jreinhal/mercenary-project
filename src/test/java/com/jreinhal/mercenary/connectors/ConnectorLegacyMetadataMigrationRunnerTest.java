package com.jreinhal.mercenary.connectors;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConnectorLegacyMetadataMigrationRunnerTest {

    @Test
    void skipsWhenMigrationAlreadyCompletedAndForceDisabled() {
        ConnectorLegacyMetadataMigrationService migrationService =
                org.mockito.Mockito.mock(ConnectorLegacyMetadataMigrationService.class);
        when(migrationService.isCompleted()).thenReturn(true);

        ConnectorLegacyMetadataMigrationRunner runner = new ConnectorLegacyMetadataMigrationRunner(migrationService);
        ReflectionTestUtils.setField(runner, "enabled", true);
        ReflectionTestUtils.setField(runner, "dryRun", false);
        ReflectionTestUtils.setField(runner, "force", false);

        runner.run();

        verify(migrationService, never()).migrateLegacyMetadata(false);
        verify(migrationService, never()).markCompleted(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dryRunDoesNotMarkCompleted() {
        ConnectorLegacyMetadataMigrationService migrationService =
                org.mockito.Mockito.mock(ConnectorLegacyMetadataMigrationService.class);
        when(migrationService.isCompleted()).thenReturn(false);
        ConnectorLegacyMetadataMigrationService.MigrationResult result =
                new ConnectorLegacyMetadataMigrationService.MigrationResult(0, 0, 0, 0L, 0, 0L, true);
        when(migrationService.migrateLegacyMetadata(true)).thenReturn(result);

        ConnectorLegacyMetadataMigrationRunner runner = new ConnectorLegacyMetadataMigrationRunner(migrationService);
        ReflectionTestUtils.setField(runner, "enabled", true);
        ReflectionTestUtils.setField(runner, "dryRun", true);
        ReflectionTestUtils.setField(runner, "force", false);

        runner.run();

        verify(migrationService).migrateLegacyMetadata(true);
        verify(migrationService, never()).markCompleted(result);
    }

    @Test
    void applyModeWithoutSyncStateDoesNotMarkCompleted() {
        ConnectorLegacyMetadataMigrationService migrationService =
                org.mockito.Mockito.mock(ConnectorLegacyMetadataMigrationService.class);
        when(migrationService.isCompleted()).thenReturn(false);
        ConnectorLegacyMetadataMigrationService.MigrationResult result =
                new ConnectorLegacyMetadataMigrationService.MigrationResult(0, 0, 0, 0L, 0, 0L, false);
        when(migrationService.migrateLegacyMetadata(false)).thenReturn(result);

        ConnectorLegacyMetadataMigrationRunner runner = new ConnectorLegacyMetadataMigrationRunner(migrationService);
        ReflectionTestUtils.setField(runner, "enabled", true);
        ReflectionTestUtils.setField(runner, "dryRun", false);
        ReflectionTestUtils.setField(runner, "force", false);

        runner.run();

        verify(migrationService).migrateLegacyMetadata(false);
        verify(migrationService, never()).markCompleted(result);
    }

    @Test
    void applyModeMarksCompletedWhenStateExists() {
        ConnectorLegacyMetadataMigrationService migrationService =
                org.mockito.Mockito.mock(ConnectorLegacyMetadataMigrationService.class);
        when(migrationService.isCompleted()).thenReturn(false);
        ConnectorLegacyMetadataMigrationService.MigrationResult result =
                new ConnectorLegacyMetadataMigrationService.MigrationResult(1, 0, 0, 0L, 0, 0L, false);
        when(migrationService.migrateLegacyMetadata(false)).thenReturn(result);

        ConnectorLegacyMetadataMigrationRunner runner = new ConnectorLegacyMetadataMigrationRunner(migrationService);
        ReflectionTestUtils.setField(runner, "enabled", true);
        ReflectionTestUtils.setField(runner, "dryRun", false);
        ReflectionTestUtils.setField(runner, "force", false);

        runner.run();

        verify(migrationService).migrateLegacyMetadata(false);
        verify(migrationService).markCompleted(result);
    }
}
