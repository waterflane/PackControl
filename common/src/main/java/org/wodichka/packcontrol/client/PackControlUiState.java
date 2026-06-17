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
                pack.packwizManifestPath,
                "Index: " + pack.packwizIndexPath,
                pack.lastGeneratedFileCount == 0 ? "Unknown" : String.valueOf(pack.lastGeneratedFileCount),
                user.includeOptionalFiles ? "Included" : "Excluded by default",
                pack.lastGenerationStatus,
                List.of(
                        "User config: config/packcontrol-user.json.",
                        "Pack config: packcontrol-pack.json in the current game directory.",
                        "Last Packwiz generation: " + pack.lastGeneratedAt + ".",
                        "GitHub push remains reserved for the next implementation stage."
                )
        );
    }
}