package org.wodichka.packcontrol.client;

import org.wodichka.packcontrol.config.PackControlConfig;

import java.util.List;

public record PackControlUiState(
        String installedVersion,
        String latestVersion,
        String channel,
        String branch,
        String syncStatus,
        String lastCheck,
        String repository,
        String releases,
        String commits,
        String issues,
        String accountStatus,
        String manifestPath,
        String indexStatus,
        String modCount,
        String optionalFiles,
        String hashStatus,
        List<String> activity
) {
    public static PackControlUiState placeholder() {
        PackControlConfig.PackControlUserConfig user = PackControlConfig.user();
        PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
        return new PackControlUiState(
                pack.installedVersion,
                pack.latestKnownVersion,
                pack.updateChannel,
                pack.branchDisplay(),
                user.autoUpdate ? "Auto update enabled" : "Manual updates only",
                pack.lastUpdateCheck,
                pack.repositoryDisplay(),
                user.useGitHubReleases ? "Enabled, not connected" : "Disabled in user config",
                "Planned",
                "Planned",
                "Not connected",
                pack.activeSnapshotPath.isBlank() ? "No snapshot loaded" : pack.activeSnapshotPath,
                "Unresolved mods: " + pack.unresolvedSnapshotMods,
                pack.lastGeneratedFileCount == 0 ? "Unknown" : String.valueOf(pack.lastGeneratedFileCount),
                user.includeOptionalFiles ? "Included" : "Excluded by default",
                pack.lastSnapshotStatus,
                List.of(
                        "Snapshot: " + pack.activeSnapshotName + ".",
                        "Snapshot path: " + (pack.activeSnapshotPath.isBlank() ? "none" : pack.activeSnapshotPath) + ".",
                        "Download: " + pack.lastDownloadStatus + ".",
                        "Backup: " + (pack.lastBackupPath.isBlank() ? "none" : pack.lastBackupPath) + "."
                )
        );
    }
}