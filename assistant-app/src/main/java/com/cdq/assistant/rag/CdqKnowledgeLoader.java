package com.cdq.assistant.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.io.Resource;

public final class CdqKnowledgeLoader implements CdqKnowledgeSource {

    public static final String SOURCE_ID = "cdq-fraud-guard";

    private final Resource snapshotResource;
    private final Resource provenanceResource;

    public CdqKnowledgeLoader(Resource snapshotResource, Resource provenanceResource) {
        this.snapshotResource = snapshotResource;
        this.provenanceResource = provenanceResource;
    }

    @Override
    public CdqKnowledgeSnapshot load() {
        byte[] snapshotBytes = readBytes(snapshotResource);
        Map<String, Object> provenance = JsonParserFactory.getJsonParser()
                .parseMap(new String(readBytes(provenanceResource), StandardCharsets.UTF_8));
        String sourceUrl = requiredString(provenance, "sourceUrl");
        String capturedAt = requiredString(provenance, "capturedAt");
        String snapshotHash = requiredString(provenance, "snapshotHash");

        return new CdqKnowledgeSnapshotFactory().create(sourceUrl, capturedAt, snapshotHash, snapshotBytes);
    }

    private static byte[] readBytes(Resource resource) {
        try {
            return resource.getContentAsByteArray();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Cannot read CDQ knowledge resource " + resource.getDescription(), exception);
        }
    }

    private static String requiredString(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("CDQ provenance is missing " + key);
        }
        return text;
    }

}
