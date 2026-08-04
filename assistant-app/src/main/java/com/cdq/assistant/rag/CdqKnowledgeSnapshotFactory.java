package com.cdq.assistant.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;

public final class CdqKnowledgeSnapshotFactory {

    public CdqKnowledgeSnapshot create(
            String sourceUrl, String capturedAt, String snapshotHash, byte[] utf8Content) {
        String actualHash = HexFormat.of().formatHex(sha256().digest(utf8Content));
        if (!snapshotHash.equals(actualHash)) {
            throw new IllegalStateException("Snapshot SHA-256 does not match provenance");
        }
        ByteArrayResource resource = new ByteArrayResource(utf8Content);
        TextReader reader = new TextReader(resource);
        reader.setCharset(StandardCharsets.UTF_8);
        reader.getCustomMetadata().putAll(Map.of(
                "sourceId", CdqKnowledgeLoader.SOURCE_ID,
                "sourceUrl", sourceUrl,
                "capturedAt", capturedAt,
                "snapshotHash", snapshotHash));
        List<Document> chunks = TokenTextSplitter.builder()
                .withChunkSize(300).withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(20).withMaxNumChunks(20)
                .withKeepSeparator(true).build().apply(reader.get());
        List<Document> indexed = IntStream.range(0, chunks.size())
                .mapToObj(index -> chunks.get(index).mutate()
                        .metadata("chunkIndex", index).build())
                .toList();
        return new CdqKnowledgeSnapshot(CdqKnowledgeLoader.SOURCE_ID,
                sourceUrl, capturedAt, snapshotHash,
                new String(utf8Content, StandardCharsets.UTF_8), indexed);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
