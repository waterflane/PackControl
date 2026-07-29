package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.PackFileResolution.Issue;
import org.wodichka.packcontrol.updateformat.PackFileResolution.IssueCode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PackFileSourceRegistry {
    private final List<PackFileSource> sources;

    public PackFileSourceRegistry(List<PackFileSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public Map<String, PackFileResolution> resolve(
            List<PackFileRequest> requests,
            CancellationToken cancellation
    ) throws IOException, InterruptedException {
        Map<PackFileSource, List<PackFileRequest>> grouped = new LinkedHashMap<>();
        Map<String, PackFileResolution> results = new LinkedHashMap<>();
        for (PackFileRequest request : requests) {
            PackFileSource source = sources.stream()
                    .filter(candidate -> candidate.supports(request.source()))
                    .findFirst()
                    .orElse(null);
            if (source == null) {
                results.put(
                        request.requestId(),
                        new PackFileResolution(
                                request.requestId(),
                                List.of(),
                                List.of(new Issue(IssueCode.INVALID_REFERENCE, "No PackFileSource supports this reference"))
                        )
                );
            } else {
                grouped.computeIfAbsent(source, ignored -> new ArrayList<>()).add(request);
            }
        }
        for (Map.Entry<PackFileSource, List<PackFileRequest>> entry : grouped.entrySet()) {
            cancellation.throwIfCancelled();
            results.putAll(entry.getKey().resolve(entry.getValue(), cancellation));
        }
        return results;
    }
}
