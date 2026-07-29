package org.wodichka.packcontrol.updateformat;

public final class ManifestValidationException extends IllegalArgumentException {
    private final ManifestValidationResult result;

    public ManifestValidationException(ManifestValidationResult result) {
        super(result.errors().size() + " manifest validation error(s); first: "
                + result.errors().getFirst().pointer() + " "
                + result.errors().getFirst().message());
        this.result = result;
    }

    public ManifestValidationResult result() {
        return result;
    }
}
