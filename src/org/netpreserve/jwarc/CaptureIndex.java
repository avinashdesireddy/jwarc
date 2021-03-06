package org.netpreserve.jwarc;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

import static java.util.Comparator.comparing;

class CaptureIndex {
    private final NavigableSet<Capture> entries = new TreeSet<>(comparing(Capture::uriKey).thenComparing(Capture::date));
    private Capture entrypoint;

    CaptureIndex(List<Path> warcs) throws IOException {
        for (Path warc : warcs) {
            try (WarcReader reader = new WarcReader(warc)) {
                for (WarcRecord record : reader) {
                    if ((record instanceof WarcResponse || record instanceof WarcResource)) {
                        WarcCaptureRecord capture = (WarcCaptureRecord) record;
                        String scheme = capture.targetURI().getScheme();
                        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                            Capture entry = new Capture(capture.targetURI(), capture.date(), warc, reader.position());
                            add(entry);
                            if (entrypoint == null && MediaType.HTML.equals(capture.payloadType().base())) {
                                entrypoint = entry;
                            }
                        }
                    }
                }
            }
        }
    }

    void add(Capture capture) {
        entries.add(capture);
    }

    NavigableSet<Capture> query(URI uri) {
        return entries.subSet(new Capture(uri, Instant.MIN), true, new Capture(uri, Instant.MAX), true);
    }

    Capture entrypoint() {
        return entrypoint;
    }
}
