package com.cdq.assistant.rag.refresh;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class HttpCdqWebsiteClient implements CdqWebsiteClient {

    private final HttpClient client;
    private final URI sourceUri;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final Clock clock;

    public HttpCdqWebsiteClient(
            HttpClient client, URI sourceUri, Duration requestTimeout, int maxResponseBytes, Clock clock) {
        this.client = client;
        this.sourceUri = sourceUri;
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.clock = clock;
    }

    @Override
    public CdqWebsitePage fetch() {
        HttpRequest request = HttpRequest.newBuilder(sourceUri)
                .timeout(requestTimeout)
                .header("Accept", "text/html")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> responseFuture = client.sendAsync(request, this::bodySubscriber);
        try {
            HttpResponse<byte[]> response = responseFuture.get(
                    requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200 || !isHtml(response.headers())) {
                throw failure(CdqKnowledgeFailureCode.SOURCE_RESPONSE_INVALID);
            }
            return new CdqWebsitePage(new String(response.body(), StandardCharsets.UTF_8), clock.instant());
        }
        catch (TimeoutException exception) {
            responseFuture.cancel(true);
            throw failure(CdqKnowledgeFailureCode.SOURCE_TIMEOUT);
        }
        catch (InterruptedException exception) {
            responseFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(CdqKnowledgeFailureCode.SOURCE_UNAVAILABLE);
        }
        catch (ExecutionException exception) {
            throw failure(failureCode(exception.getCause()));
        }
    }

    private HttpResponse.BodySubscriber<byte[]> bodySubscriber(HttpResponse.ResponseInfo responseInfo) {
        if (responseInfo.statusCode() != 200 || !isHtml(responseInfo.headers())) {
            return new CancellingBodySubscriber();
        }
        return new BoundedBodySubscriber(maxResponseBytes);
    }

    private CdqKnowledgeFailureCode failureCode(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ResponseTooLargeException) {
                return CdqKnowledgeFailureCode.SOURCE_RESPONSE_INVALID;
            }
            if (current instanceof HttpTimeoutException || current instanceof TimeoutException) {
                return CdqKnowledgeFailureCode.SOURCE_TIMEOUT;
            }
            current = current.getCause();
        }
        return CdqKnowledgeFailureCode.SOURCE_UNAVAILABLE;
    }

    private boolean isHtml(HttpHeaders headers) {
        return headers.firstValue("Content-Type")
                .map(value -> value.split(";", 2)[0].trim())
                .map("text/html"::equalsIgnoreCase)
                .orElse(false);
    }

    private CdqKnowledgeOperationException failure(CdqKnowledgeFailureCode code) {
        return new CdqKnowledgeOperationException(code);
    }

    private static final class CancellingBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> body = new CompletableFuture<>();

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
            body.complete(new byte[0]);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            // The subscription is cancelled before response bytes are requested.
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(new byte[0]);
        }
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxResponseBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int receivedBytes;

        private BoundedBodySubscriber(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int chunkBytes = buffer.remaining();
                if ((long) receivedBytes + chunkBytes > maxResponseBytes) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] chunk = new byte[chunkBytes];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
                receivedBytes += chunkBytes;
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException {
    }
}
