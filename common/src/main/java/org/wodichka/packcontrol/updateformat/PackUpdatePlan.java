package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;

import java.util.List;

public record PackUpdatePlan(
        List<Operation> operations,
        List<Issue> errors,
        List<Issue> warnings
) {
    public PackUpdatePlan {
        operations = List.copyOf(operations);
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public boolean isBlocked() {
        return !errors.isEmpty();
    }

    public long count(OperationType type) {
        return operations.stream().filter(operation -> operation.type() == type).count();
    }

    public enum OperationType {
        ADD,
        REPLACE,
        KEEP,
        REMOVE
    }

    public enum ContentKind {
        DOWNLOAD,
        OVERRIDE
    }

    public record Operation(
            OperationType type,
            String path,
            ContentKind contentKind,
            List<String> downloads,
            Hashes hashes,
            long size,
            boolean required
    ) {
        public Operation {
            downloads = downloads == null ? List.of() : List.copyOf(downloads);
        }
    }

    public enum IssueCode {
        INVALID_MANIFEST,
        INVALID_INSTALLED_STATE,
        MISSING_REQUIRED_SOURCE,
        UNMANAGED_PATH_CONFLICT,
        UNREADABLE_PATH,
        UNMANAGED_REMOVE_IGNORED,
        OPTIONAL_SOURCE_UNAVAILABLE
    }

    public record Issue(
            IssueCode code,
            String pointer,
            String message
    ) {
    }
}
