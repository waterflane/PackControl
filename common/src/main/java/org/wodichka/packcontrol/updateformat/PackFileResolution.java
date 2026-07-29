package org.wodichka.packcontrol.updateformat;

import java.util.List;
import java.util.Objects;

public record PackFileResolution(
        String requestId,
        List<PackFileCandidate> candidates,
        List<Issue> issues
) {
    public PackFileResolution {
        Objects.requireNonNull(requestId, "requestId");
        candidates = List.copyOf(candidates);
        issues = List.copyOf(issues);
    }

    public boolean resolved() {
        return !candidates.isEmpty();
    }

    public enum IssueCode {
        INVALID_REFERENCE,
        NOT_FOUND,
        DISALLOWED_URL,
        INVALID_METADATA,
        HASH_MISMATCH
    }

    public record Issue(IssueCode code, String message) {
        public Issue {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
