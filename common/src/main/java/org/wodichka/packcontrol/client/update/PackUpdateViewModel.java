package org.wodichka.packcontrol.client.update;

import java.util.List;

public record PackUpdateViewModel(
        Stage stage,
        String installedVersion,
        String availableVersion,
        String changelog,
        String message,
        PackUpdateSummary summary,
        List<String> details,
        boolean rollbackSuggested
) {
    public PackUpdateViewModel {
        installedVersion = text(installedVersion, "Unknown");
        availableVersion = text(availableVersion, "Not checked");
        changelog = text(changelog, "Check for updates to view the changelog.");
        message = text(message, "");
        details = details == null ? List.of() : List.copyOf(details);
    }

    public static PackUpdateViewModel idle(String installedVersion, String availableVersion) {
        return new PackUpdateViewModel(
                Stage.IDLE,
                installedVersion,
                availableVersion,
                "Check for updates to view the changelog.",
                "Ready to check for updates.",
                null,
                List.of(),
                false
        );
    }

    public boolean busy() {
        return stage == Stage.CHECKING
                || stage == Stage.PREPARING
                || stage == Stage.APPLYING
                || stage == Stage.ROLLING_BACK;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public enum Stage {
        IDLE,
        CHECKING,
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        PREPARING,
        READY_TO_RESTART,
        APPLYING,
        ERROR,
        ROLLING_BACK,
        ROLLED_BACK
    }
}
