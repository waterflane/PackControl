package org.wodichka.packcontrol.updateformat;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface PackFileSource {
    String sourceId();

    boolean supports(PackSourceReference reference);

    Map<String, PackFileResolution> resolve(
            List<PackFileRequest> requests,
            CancellationToken cancellation
    ) throws IOException, InterruptedException;
}
