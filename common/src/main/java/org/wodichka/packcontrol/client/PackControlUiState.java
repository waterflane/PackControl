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
        PackControlConfig config = PackControlConfig.get();
        return new PackControlUiState(
                config.installedVersion,
                config.latestKnownVersion,
                config.updateChannel,
                config.branchDisplay(),
                config.updateModeDisplay(),
                config.lastUpdateCheck,
                config.repositoryDisplay(),
                config.useGitHubReleases ? "Enabled, not connected" : "Disabled in config",
                "Planned",
                "Planned",
                "Not connected",
                config.packwizManifestPath,
                config.packwizStatusDisplay(),
                "Unknown",
                config.includeOptionalFiles ? "Included" : "Excluded by default",
                config.verifyAfterUpdate ? "Verify after update" : "Not checked",
                List.of(
                        "Config file: config/packcontrol.json.",
                        "Repository and branch are loaded from the PackControl config.",
                        "Auto update is " + (config.autoUpdate ? "enabled" : "disabled") + "; manual confirmation is " + (config.requireManualConfirmation ? "required" : "not required") + ".",
                        "Packwiz sync remains UI-only until the update engine is implemented."
                )
        );
    }
}