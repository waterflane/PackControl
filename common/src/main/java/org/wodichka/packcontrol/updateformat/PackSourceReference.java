package org.wodichka.packcontrol.updateformat;

import java.net.URI;
import java.util.List;

public sealed interface PackSourceReference
        permits PackSourceReference.ModrinthReference,
        PackSourceReference.GitHubReleaseReference,
        PackSourceReference.DirectHttpsReference {

    record ModrinthReference(String preferredFileName) implements PackSourceReference {
    }

    record GitHubReleaseReference(
            String owner,
            String repository,
            String tag,
            List<String> assetNames
    ) implements PackSourceReference {
        public GitHubReleaseReference {
            assetNames = assetNames == null ? List.of() : List.copyOf(assetNames);
        }
    }

    record DirectHttpsReference(List<URI> urls) implements PackSourceReference {
        public DirectHttpsReference {
            urls = urls == null ? List.of() : List.copyOf(urls);
        }
    }
}
