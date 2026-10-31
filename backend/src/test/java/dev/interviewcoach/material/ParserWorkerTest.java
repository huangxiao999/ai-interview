package dev.interviewcoach.material;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ParserWorkerTest {
    private static final String BOUNDARY = "J".repeat(19_999) + "😀";

    static Stream<Arguments> documentTextCases() {
        String multiline = "J".repeat(9_999) + "\n" + "K".repeat(9_999) + "😀";
        return Stream.of(
                Arguments.of("exact-limit-with-extractor-newline", BOUNDARY, BOUNDARY, "READY"),
                Arguments.of("leading-and-trailing-whitespace", " \t" + BOUNDARY + "\r\n\t ", BOUNDARY, "READY"),
                Arguments.of("internal-crlf", multiline.replace("\n", "\r\n"), multiline, "READY"),
                Arguments.of("internal-cr", multiline.replace('\n', '\r'), multiline, "READY"),
                Arguments.of("normalized-over-limit", " " + BOUNDARY + "X\r\n", null, "TEXT_TOO_LONG"),
                Arguments.of("blank", " \t\r\n ", null, "NO_TEXT"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentTextCases")
    void normalizesActualDocxBeforeApplyingCodepointLimit(String name, String raw,
            String expectedBody, String expectedCode) throws Exception {
        Path base = Files.createTempDirectory(Files.createDirectories(Path.of("../.cache/tmp").toAbsolutePath()), "parser-text-");
        var files = new PrivateFiles(base.resolve("private").toString());
        UUID id = UUID.randomUUID();
        try (var doc = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(raw);
            doc.write(bytes);
            files.stage(id, bytes.toByteArray());
        }
        // 确认真实提取仍保留待处理换行，避免夹具在进入子进程前已经消除了回归条件。
        String extracted = ParserWorker.extract(files.path(id, "bin"), "DOCX");
        assertThat(extracted).startsWith(raw).endsWith("\n");
        if (expectedBody != null) {
            assertThat(expectedBody.codePointCount(0, expectedBody.length())).isEqualTo(20_000);
            assertThat(extracted.codePointCount(0, extracted.length())).isGreaterThan(20_000);
        }
        var result = new DocumentParser(files).parse(id, "DOCX");
        assertThat(result.code()).isEqualTo(expectedCode);
        assertThat(result.body()).isEqualTo(expectedBody);
    }

    @ParameterizedTest
    @ValueSource(ints = {20_000, 20_001})
    void appliesSameLimitToActualPdfWithTrailingNewline(int codepoints) throws Exception {
        Path base = Files.createTempDirectory(Files.createDirectories(Path.of("../.cache/tmp").toAbsolutePath()), "parser-pdf-");
        var files = new PrivateFiles(base.resolve("private").toString());
        UUID id = UUID.randomUUID();
        String firstLine = "J".repeat(codepoints - 19_800);
        String expectedBody = firstLine + ("\n" + "J".repeat(199)).repeat(99);
        try (var doc = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 4);
                content.setLeading(6);
                content.newLineAtOffset(40, 760);
                content.showText(firstLine);
                for (int line = 1; line < 100; line++) {
                    content.newLine();
                    content.showText("J".repeat(199));
                }
                content.endText();
            }
            doc.save(bytes);
            files.stage(id, bytes.toByteArray());
        }
        assertThat(expectedBody.codePointCount(0, expectedBody.length())).isEqualTo(codepoints);
        assertThat(ParserWorker.extract(files.path(id, "bin"), "PDF"))
                .isEqualTo(expectedBody.replace("\n", System.lineSeparator()) + System.lineSeparator());
        var result = new DocumentParser(files).parse(id, "PDF");
        assertThat(result.code()).isEqualTo(codepoints == 20_000 ? "READY" : "TEXT_TOO_LONG");
        assertThat(result.body()).isEqualTo(codepoints == 20_000 ? expectedBody : null);
    }
}
