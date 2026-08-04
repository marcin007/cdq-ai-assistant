package com.cdq.assistant.rag.refresh;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpCdqWebsiteClientTest {

    private HttpServer server;
    private ExecutorService executor;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        baseUri = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void returnsUtf8HtmlAndCaptureTimeForAnAcceptedResponse() {
        server.createContext("/source", exchange -> respond(exchange, 200, "text/html; charset=UTF-8", "<h1>CDQ</h1>"));

        CdqWebsitePage page = client("/source", Duration.ofSeconds(1), 1024).fetch();

        assertThat(page.html()).isEqualTo("<h1>CDQ</h1>");
        assertThat(page.capturedAt()).isEqualTo(Instant.parse("2026-08-04T10:00:00Z"));
    }

    @Test
    void rejectsRedirectsWithoutFollowingTheirLocation() {
        server.createContext("/source", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://example.test/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        assertFailure(() -> client("/source", Duration.ofSeconds(1), 1024).fetch(),
                CdqKnowledgeFailureCode.SOURCE_RESPONSE_INVALID);
    }

    @Test
    void rejectsNonHtmlResponses() {
        server.createContext("/source", exchange -> respond(exchange, 200, "application/json", "{}"));

        assertFailure(() -> client("/source", Duration.ofSeconds(1), 1024).fetch(),
                CdqKnowledgeFailureCode.SOURCE_RESPONSE_INVALID);
    }

    @Test
    void rejectsNonSuccessfulResponses() {
        server.createContext("/source", exchange -> respond(exchange, 500, "text/html", "failure"));

        assertFailure(() -> client("/source", Duration.ofSeconds(1), 1024).fetch(),
                CdqKnowledgeFailureCode.SOURCE_RESPONSE_INVALID);
    }

    @Test
    void rejectsAResponseBodyLargerThanItsConfiguredLimit() {
        server.createContext("/source", exchange -> respond(exchange, 200, "text/html", "x".repeat(1025)));

        assertFailure(() -> client("/source", Duration.ofSeconds(1), 1024).fetch(),
                CdqKnowledgeFailureCode.SOURCE_RESPONSE_INVALID);
    }

    @Test
    void mapsARequestTimeoutToTheSafeTimeoutCode() {
        server.createContext("/source", exchange -> {
            try {
                Thread.sleep(500);
                respond(exchange, 200, "text/html", "<h1>late</h1>");
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        assertFailure(() -> client("/source", Duration.ofMillis(100), 1024).fetch(),
                CdqKnowledgeFailureCode.SOURCE_TIMEOUT);
    }

    @Test
    void appliesTheRequestDeadlineAfterHeadersWhenTheBodyStalls() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        server.createContext("/source", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            try {
                releaseBody.await();
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            finally {
                exchange.close();
            }
        });

        try {
            assertFailureWithin(
                    () -> client("/source", Duration.ofMillis(100), 1024).fetch(),
                    CdqKnowledgeFailureCode.SOURCE_TIMEOUT,
                    Duration.ofSeconds(1));
        }
        finally {
            releaseBody.countDown();
        }
    }

    @Test
    void appliesOneWholeResponseDeadlineToATrickledBody() throws Exception {
        server.createContext("/source", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 0);
            try {
                for (int index = 0; index < 20; index++) {
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    Thread.sleep(25);
                }
            }
            catch (IOException ignored) {
                // The client is expected to cancel the response when its deadline expires.
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            finally {
                exchange.close();
            }
        });

        assertFailureWithin(
                () -> client("/source", Duration.ofMillis(100), 1024).fetch(),
                CdqKnowledgeFailureCode.SOURCE_TIMEOUT,
                Duration.ofSeconds(1));
    }

    @Test
    void cancelsAnInvalidResponseBodyInsteadOfLeavingItOpen() throws Exception {
        CountDownLatch clientDisconnected = new CountDownLatch(1);
        AtomicBoolean stopWriting = new AtomicBoolean();
        server.createContext("/source", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(500, 0);
            byte[] chunk = new byte[64 * 1024];
            try {
                while (!stopWriting.get()) {
                    exchange.getResponseBody().write(chunk);
                    exchange.getResponseBody().flush();
                }
            }
            catch (IOException exception) {
                clientDisconnected.countDown();
            }
            finally {
                exchange.close();
            }
        });

        try {
            assertFailure(() -> client("/source", Duration.ofSeconds(2), 1024).fetch(),
                    CdqKnowledgeFailureCode.SOURCE_RESPONSE_INVALID);
            assertThat(clientDisconnected.await(1, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            stopWriting.set(true);
        }
    }

    private HttpCdqWebsiteClient client(String path, Duration timeout, int maxResponseBytes) {
        return new HttpCdqWebsiteClient(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                baseUri.resolve(path),
                timeout,
                maxResponseBytes,
                Clock.fixed(Instant.parse("2026-08-04T10:00:00Z"), ZoneOffset.UTC));
    }

    private void assertFailure(ThrowingOperation operation, CdqKnowledgeFailureCode expectedCode) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(CdqKnowledgeOperationException.class)
                .extracting(error -> ((CdqKnowledgeOperationException) error).code())
                .isEqualTo(expectedCode);
    }

    private void assertFailureWithin(
            ThrowingOperation operation,
            CdqKnowledgeFailureCode expectedCode,
            Duration outerTimeout) throws Exception {
        ExecutorService fetchExecutor = Executors.newSingleThreadExecutor();
        Future<CdqKnowledgeFailureCode> failure = fetchExecutor.submit(() -> {
            try {
                operation.run();
                return null;
            }
            catch (CdqKnowledgeOperationException exception) {
                return exception.code();
            }
        });
        try {
            assertThat(failure.get(outerTimeout.toMillis(), TimeUnit.MILLISECONDS))
                    .isEqualTo(expectedCode);
        }
        catch (TimeoutException exception) {
            throw new AssertionError("The website client exceeded the whole-response deadline", exception);
        }
        finally {
            failure.cancel(true);
            fetchExecutor.shutdownNow();
        }
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
