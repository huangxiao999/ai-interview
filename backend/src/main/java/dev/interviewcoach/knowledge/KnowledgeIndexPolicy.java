package dev.interviewcoach.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 有界的工程分块策略，不代表真实检索效果已定标；偏移量为Java UTF-16索引。 */
final class KnowledgeIndexPolicy {
    static final String REVISION = "paragraph-1000cp-batch10-no-overlap-v1";
    static final int DIMENSION = 1024;
    static final int SERVICE_SECONDS = 60;
    static final int QUEUE_SECONDS = 20;

    static List<List<Chunk>> batches(String body) {
        var chunks = new ArrayList<Chunk>();
        int start = 0;
        while (start < body.length()) {
            int remaining = body.codePointCount(start, body.length());
            int end = body.offsetByCodePoints(start, Math.min(1000, remaining));
            int paragraph = body.lastIndexOf("\n\n", end - 2);
            if (end < body.length() && paragraph > start) end = paragraph + 2;
            String text = body.substring(start, end);
            chunks.add(new Chunk(UUID.randomUUID(), chunks.size(), start, end, text, hash(text)));
            start = end;
        }
        var result = new ArrayList<List<Chunk>>();
        for (int i = 0; i < chunks.size(); i += 10) result.add(List.copyOf(chunks.subList(i, Math.min(i + 10, chunks.size()))));
        return List.copyOf(result);
    }

    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    record Chunk(UUID id, int ordinal, int startOffset, int endOffset, String text, String digest) { }
}
