package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.PackFileResolution.Issue;
import org.wodichka.packcontrol.updateformat.PackFileResolution.IssueCode;
import org.wodichka.packcontrol.updateformat.PackSourceReference.DirectHttpsReference;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DirectHttpsSource implements PackFileSource {
    private final Set<String> allowedDomains;

    public DirectHttpsSource(Set<String> allowedDomains) {
        if (allowedDomains == null || allowedDomains.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed domain is required");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String domain : allowedDomains) {
            if (domain == null || domain.isBlank()) {
                throw new IllegalArgumentException("Allowed domain must not be blank");
            }
            normalized.add(domain.toLowerCase(Locale.ROOT));
        }
        this.allowedDomains = Set.copyOf(normalized);
    }

    @Override
    public String sourceId() {
        return "direct-https";
    }

    @Override
    public boolean supports(PackSourceReference reference) {
        return reference instanceof DirectHttpsReference;
    }

    @Override
    public Map<String, PackFileResolution> resolve(
            List<PackFileRequest> requests,
            CancellationToken cancellation
    ) throws CancellationToken.PackRequestCancelledException {
        Map<String, PackFileResolution> results = new LinkedHashMap<>();
        for (PackFileRequest request : requests) {
            cancellation.throwIfCancelled();
            if (!(request.source() instanceof DirectHttpsReference reference)) {
                results.put(
                        request.requestId(),
                        new PackFileResolution(
                                request.requestId(),
                                List.of(),
                                List.of(new Issue(IssueCode.INVALID_REFERENCE, "Request is not a direct HTTPS reference"))
                        )
                );
                continue;
            }
            List<PackFileCandidate> candidates = new ArrayList<>();
            List<Issue> issues = new ArrayList<>();
            Set<URI> seen = new LinkedHashSet<>();
            for (URI uri : reference.urls()) {
                if (!allowed(uri)) {
                    issues.add(new Issue(IssueCode.DISALLOWED_URL, "Direct URL is not allowed: " + uri));
                    continue;
                }
                if (seen.add(uri)) {
                    String path = uri.getPath();
                    int slash = path == null ? -1 : path.lastIndexOf('/');
                    String fileName = slash >= 0 ? path.substring(slash + 1) : request.path();
                    candidates.add(new PackFileCandidate(
                            uri,
                            sourceId(),
                            fileName,
                            request.size(),
                            request.hashes(),
                            allowedDomains
                    ));
                }
            }
            if (candidates.isEmpty() && issues.isEmpty()) {
                issues.add(new Issue(IssueCode.NOT_FOUND, "Direct source has no URLs"));
            }
            results.put(
                    request.requestId(),
                    new PackFileResolution(request.requestId(), candidates, issues)
            );
        }
        return results;
    }

    private boolean allowed(URI uri) {
        if (uri == null
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return allowedDomains.stream().anyMatch(domain ->
                host.equals(domain) || host.endsWith("." + domain)
        );
    }
}
