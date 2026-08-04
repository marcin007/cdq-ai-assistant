package com.cdq.assistant.rag.refresh;

import java.time.Instant;

public record CdqWebsitePage(String html, Instant capturedAt) {
}
