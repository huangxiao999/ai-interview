package dev.interviewcoach.knowledge;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

record KnowledgeInput(String title, String body, String sourceUrl, String sourceAnchor,
        String applicability, String usageNote, String verificationNote, LocalDate verifiedOn) {

    // 工程保护值限定一次人工提交的内存/传输规模，不代表知识语义覆盖或简历输入规则。
    KnowledgeInput normalized() {
        var result = new KnowledgeInput(text(title, 200), text(body, 100_000), text(sourceUrl, 2_048),
                text(sourceAnchor, 1_000), text(applicability, 4_000), text(usageNote, 2_000),
                text(verificationNote, 2_000), verifiedOn);
        if (verifiedOn == null) throw new InvalidKnowledge("请填写来源核实日期");
        try {
            URI uri = URI.create(result.sourceUrl());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new InvalidKnowledge("来源须为不含凭证的 HTTP/HTTPS 链接");
        }
        return result;
    }

    static String text(String value, int limit) {
        if (value == null) throw new InvalidKnowledge("必填内容不能为空");
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isEmpty() || normalized.indexOf('\0') >= 0
                || normalized.codePointCount(0, normalized.length()) > limit) {
            throw new InvalidKnowledge("内容为空、包含无效字符或超过输入上限");
        }
        return normalized;
    }

    String digest() {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            // 长度前缀消除字段分隔歧义；固定字段顺序与规范化策略属于v1摘要契约。
            for (String field : new String[]{title, body, sourceUrl, sourceAnchor, applicability,
                    usageNote, verificationNote, verifiedOn.toString()}) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}

class InvalidKnowledge extends RuntimeException {
    InvalidKnowledge(String message) { super(message); }
}
class KnowledgeConflict extends RuntimeException { }
class KnowledgeStateConflict extends RuntimeException { }
class KnowledgeUnavailable extends RuntimeException { }
class KnowledgeAccountChanged extends RuntimeException { }
