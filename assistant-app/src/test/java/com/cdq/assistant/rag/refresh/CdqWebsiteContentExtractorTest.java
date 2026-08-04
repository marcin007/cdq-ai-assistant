package com.cdq.assistant.rag.refresh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CdqWebsiteContentExtractorTest {

    @Test
    void extractsExactlyTheReviewedSnapshotAndIgnoresPageChrome() throws Exception {
        String html = fixture("fixtures/cdq-fraud-guard-page.html");
        String expected = Files.readString(Path.of("..", "knowledge", "cdq-fraud-guard.txt"));

        CdqExtractedContent result = new CdqWebsiteContentExtractor().extract(html);

        assertThat(result.text()).isEqualTo(expected);
        assertThat(result.snapshotHash())
                .isEqualTo("35fe98e4df21b5811132758f3aa805b704b8ba948d9fe6384d30cfaf0b6f30cc");
    }

    @Test
    void preservesTheHashWhenOnlyNavigationChanges() throws Exception {
        String html = fixture("fixtures/cdq-fraud-guard-page.html")
                .replace("Products</a><a href=\"/company\">Company", "Solutions</a><a href=\"/company\">About");

        CdqExtractedContent result = new CdqWebsiteContentExtractor().extract(html);

        assertThat(result.snapshotHash())
                .isEqualTo("35fe98e4df21b5811132758f3aa805b704b8ba948d9fe6384d30cfaf0b6f30cc");
    }

    @Test
    void ignoresWrappedLinkAndImageOnlyCallToActionContent() throws Exception {
        String originalHtml = fixture("fixtures/cdq-fraud-guard-page.html");
        String htmlWithCallToAction = originalHtml.replace(
                "<h2>Related Readings</h2>",
                "<p><span><a href=\"/request-demo\">Request demo</a></span><img src=\"/demo.svg\" alt=\"\"></p>"
                        + "<h2>Related Readings</h2>");

        CdqExtractedContent original = new CdqWebsiteContentExtractor().extract(originalHtml);
        CdqExtractedContent withCallToAction = new CdqWebsiteContentExtractor().extract(htmlWithCallToAction);

        assertThat(withCallToAction.text()).isEqualTo(original.text());
        assertThat(withCallToAction.snapshotHash()).isEqualTo(original.snapshotHash());
    }

    @Test
    void changesTheHashWhenAProductParagraphChanges() throws Exception {
        CdqExtractedContent original = new CdqWebsiteContentExtractor()
                .extract(fixture("fixtures/cdq-fraud-guard-page.html"));
        CdqExtractedContent changed = new CdqWebsiteContentExtractor()
                .extract(fixture("fixtures/cdq-fraud-guard-page-changed.html"));

        assertThat(changed.snapshotHash()).isNotEqualTo(original.snapshotHash());
    }

    @Test
    void stopsAtTheExactRelatedReadingsHeadingWhenItUsesH4() throws Exception {
        String originalHtml = fixture("fixtures/cdq-fraud-guard-page.html");
        String htmlWithH4Boundary = originalHtml.replace(
                "<h2>Related Readings</h2>",
                "<h4>Related Readings</h4><p>Related article text must stay outside product knowledge.</p>");

        CdqExtractedContent original = new CdqWebsiteContentExtractor().extract(originalHtml);
        CdqExtractedContent withH4Boundary = new CdqWebsiteContentExtractor().extract(htmlWithH4Boundary);

        assertThat(withH4Boundary).isEqualTo(original);
    }

    @Test
    void retainsAnOrdinaryParagraphThatMentionsRelatedReadings() throws Exception {
        String html = fixture("fixtures/cdq-fraud-guard-page.html").replace(
                "<h2>Related Readings</h2>",
                "<p>Our analyst discusses Related Readings in this product paragraph.</p>"
                        + "<h2>Related Readings</h2>");

        CdqExtractedContent extracted = new CdqWebsiteContentExtractor().extract(html);

        assertThat(extracted.text())
                .contains("Our analyst discusses Related Readings in this product paragraph.")
                .doesNotContain("Changed related article");
    }

    @Test
    void rejectsMoreThanOneThousandNormalizedLines() {
        String denseLines = IntStream.range(0, 1_001)
                .mapToObj(index -> "x")
                .collect(Collectors.joining("<br>"));
        String html = """
                <article>
                  <h1>CDQ Fraud Guard</h1>
                  <h2>Key Features of CDQ Fraud Guard</h2>
                  <p>%s</p>
                  <h2>Related Readings</h2>
                </article>
                """.formatted(denseLines);

        assertInvalid(html);
    }

    @Test
    void rejectsMissingProductHeading() {
        assertInvalid("<article><h2>Key Features of CDQ Fraud Guard</h2><h2>Related Readings</h2></article>");
    }

    @Test
    void rejectsDuplicateProductHeadings() throws Exception {
        String html = fixture("fixtures/cdq-fraud-guard-page.html")
                .replace("<h2>Related Readings</h2>", "<h1>CDQ Fraud Guard</h1><h2>Related Readings</h2>");

        assertInvalid(html);
    }

    @Test
    void rejectsMissingRelatedReadingsBoundary() throws Exception {
        String html = fixture("fixtures/cdq-fraud-guard-page.html")
                .replace("<h2>Related Readings</h2>", "<h2>More information</h2>");

        assertInvalid(html);
    }

    @Test
    void rejectsContentShorterThanFiveHundredCharacters() {
        assertInvalid("""
                <article>
                  <h1>CDQ Fraud Guard</h1>
                  <h2>Key Features of CDQ Fraud Guard</h2>
                  <p>Short reviewed text.</p>
                  <h2>Related Readings</h2>
                </article>
                """);
    }

    @Test
    void rejectsContentLongerThanFiftyThousandCharacters() {
        assertInvalid("""
                <article>
                  <h1>CDQ Fraud Guard</h1>
                  <h2>Key Features of CDQ Fraud Guard</h2>
                  <p>%s</p>
                  <h2>Related Readings</h2>
                </article>
                """.formatted("a".repeat(50_001)));
    }

    private void assertInvalid(String html) {
        assertThatThrownBy(() -> new CdqWebsiteContentExtractor().extract(html))
                .isInstanceOf(CdqKnowledgeOperationException.class)
                .extracting(error -> ((CdqKnowledgeOperationException) error).code())
                .isEqualTo(CdqKnowledgeFailureCode.SOURCE_CONTENT_INVALID);
    }

    private String fixture(String path) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
