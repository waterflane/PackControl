package org.wodichka.packcontrol.updateformat;

import java.util.Objects;

/**
 * A stable machine-readable code plus a JSON Pointer and human-readable
 * explanation.
 */
public record ManifestValidationError(
        ManifestErrorCode code,
        String pointer,
        String message
) {
    public ManifestValidationError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(message, "message");
    }
}
