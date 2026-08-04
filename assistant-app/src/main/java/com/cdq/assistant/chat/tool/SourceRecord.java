package com.cdq.assistant.chat.tool;

import java.net.URI;

public record SourceRecord(SourceKind kind, String label, URI url) {

    static SourceRecord from(SourceKind kind) {
        return switch (kind) {
            case CDQ_RAG -> new SourceRecord(
                    kind,
                    "CDQ Fraud Guard",
                    URI.create("https://www.cdq.com/products/cdq-fraud-guard"));
            case REST_COUNTRIES ->
                new SourceRecord(kind, "REST Countries v5", URI.create("https://restcountries.com/"));
            case WEATHER ->
                new SourceRecord(
                        kind,
                        "WeatherAPI via semdin/mcp-weather",
                        URI.create("https://github.com/semdin/mcp-weather"));
        };
    }
}
