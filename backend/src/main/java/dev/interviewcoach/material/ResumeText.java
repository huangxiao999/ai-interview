package dev.interviewcoach.material;

final class ResumeText {

    private ResumeText() {
    }

    static Normalized normalize(String raw) {
        return normalize(raw, 100, 20_000, "简历正文需为100至20000个字符");
    }

    static Normalized normalizeJd(String raw) {
        return normalize(raw, 50, 10_000, "JD需为50至10000个字符");
    }

    private static Normalized normalize(String raw, int minimum, int maximum, String message) {
        if (raw == null) {
            throw new InvalidMaterial(message);
        }
        String body = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        int count = body.codePointCount(0, body.length());
        if (count < minimum || count > maximum) {
            throw new InvalidMaterial(message);
        }
        return new Normalized(body, count);
    }

    record Normalized(String body, int codepointCount) {
    }
}

final class InvalidMaterial extends RuntimeException {
    InvalidMaterial(String message) {
        super(message);
    }
}

final class MaterialUnavailable extends RuntimeException {
    MaterialUnavailable() {
        super("资料不存在或不可访问");
    }
}
