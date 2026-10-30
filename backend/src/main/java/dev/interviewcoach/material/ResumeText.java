package dev.interviewcoach.material;

final class ResumeText {

    private ResumeText() {
    }

    static Normalized normalize(String raw) {
        if (raw == null) {
            throw new InvalidMaterial("简历正文需为100至20000个字符");
        }
        String body = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        int count = body.codePointCount(0, body.length());
        if (count < 100 || count > 20_000) {
            throw new InvalidMaterial("简历正文需为100至20000个字符");
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
