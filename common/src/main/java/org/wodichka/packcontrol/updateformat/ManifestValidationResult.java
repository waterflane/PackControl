package org.wodichka.packcontrol.updateformat;

import java.util.List;

public record ManifestValidationResult(List<ManifestValidationError> errors) {
    public ManifestValidationResult {
        errors = List.copyOf(errors);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public void throwIfInvalid() {
        if (!isValid()) {
            throw new ManifestValidationException(this);
        }
    }
}
