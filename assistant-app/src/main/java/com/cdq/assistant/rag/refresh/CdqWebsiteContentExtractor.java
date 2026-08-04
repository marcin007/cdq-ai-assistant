package com.cdq.assistant.rag.refresh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public final class CdqWebsiteContentExtractor {

    private static final long MAX_NORMALIZED_LINES = 1_000;

    public CdqExtractedContent extract(String html) {
        Document document = Jsoup.parse(html);
        List<Element> headings = document.select("h1").stream()
                .filter(element -> normalizeBlock(element.text()).equals("CDQ Fraud Guard"))
                .toList();
        if (headings.size() != 1) {
            throw invalidContent();
        }

        Element productHeading = headings.getFirst();
        ExtractionScope extractionScope = scopeContainingRelatedReadings(productHeading);
        List<String> blocks = new ArrayList<>();
        boolean started = false;
        boolean previousWasListItem = false;
        for (Element element : extractionScope.scope().select("h1,h2,h3,h4,h5,h6,p,li")) {
            if (element == extractionScope.boundary()) {
                break;
            }
            if (element == productHeading) {
                started = true;
            }
            if (!started) {
                continue;
            }
            String block = normalizeBlock(blockText(element));
            if (block.isBlank() || isImageOrCallToActionOnly(element)) {
                continue;
            }
            if (element.tagName().equals("li")) {
                if (previousWasListItem) {
                    int lastBlock = blocks.size() - 1;
                    blocks.set(lastBlock, blocks.get(lastBlock) + "\n- " + block);
                }
                else {
                    blocks.add("- " + block);
                }
                previousWasListItem = true;
            }
            else {
                blocks.add(block);
                previousWasListItem = false;
            }
        }

        String text = String.join("\n\n", blocks) + "\n";
        if (!blocks.contains("Key Features of CDQ Fraud Guard")
                || text.length() < 500 || text.length() > 50_000
                || text.lines().count() > MAX_NORMALIZED_LINES) {
            throw invalidContent();
        }
        return new CdqExtractedContent(text, sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    private ExtractionScope scopeContainingRelatedReadings(Element productHeading) {
        Element current = productHeading;
        while (current != null) {
            List<Element> headings = current.select("h1,h2,h3,h4,h5,h6");
            int productIndex = headings.indexOf(productHeading);
            if (productIndex >= 0) {
                for (int index = productIndex + 1; index < headings.size(); index++) {
                    Element heading = headings.get(index);
                    if (normalizeBlock(heading.text()).equals("Related Readings")) {
                        return new ExtractionScope(current, heading);
                    }
                }
            }
            current = current.parent();
        }
        throw invalidContent();
    }

    private String normalizeBlock(String text) {
        return text.replace('\u00A0', ' ').replaceAll("[\\t\\x0B\\f\\r ]+", " ").trim();
    }

    private String blockText(Element element) {
        if (element.select("br").isEmpty()) {
            return element.text();
        }
        Element copy = element.clone();
        copy.select("br").forEach(lineBreak -> lineBreak.replaceWith(new org.jsoup.nodes.TextNode("\n")));
        return copy.wholeText();
    }

    private boolean isImageOrCallToActionOnly(Element element) {
        if (element.closest("nav,footer") != null) {
            return true;
        }
        List<Element> meaningfulDescendants = element.getAllElements().stream()
                .filter(descendant -> descendant != element)
                .filter(descendant -> descendant.children().isEmpty())
                .filter(descendant -> !descendant.tagName().equals("img"))
                .filter(descendant -> !normalizeBlock(descendant.ownText()).isBlank())
                .toList();
        return normalizeBlock(element.ownText()).isBlank()
                && !meaningfulDescendants.isEmpty()
                && meaningfulDescendants.stream().allMatch(descendant -> descendant.closest("a") != null);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private CdqKnowledgeOperationException invalidContent() {
        return new CdqKnowledgeOperationException(CdqKnowledgeFailureCode.SOURCE_CONTENT_INVALID);
    }

    private record ExtractionScope(Element scope, Element boundary) {
    }
}
