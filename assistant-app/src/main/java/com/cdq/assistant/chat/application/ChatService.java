package com.cdq.assistant.chat.application;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ChatService implements ChatUseCase {

    private final ChatOperation operation;
    private final ExecutorService executor;
    private final Duration timeout;

    public ChatService(
            ChatOperation operation, ExecutorService executor, Duration timeout) {
        this.operation = Objects.requireNonNull(operation);
        this.executor = Objects.requireNonNull(executor);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Chat timeout must be positive");
        }
        this.timeout = timeout;
    }

    @Override
    public ChatResult chat(String message) {
        Future<ChatResult> future = executor.submit(() -> operation.chat(message));
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new ChatTimeoutException();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ChatDependencyException(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof ChatTimeoutException timeoutException) {
                throw timeoutException;
            }
            if (cause instanceof ChatDependencyException dependencyException) {
                throw dependencyException;
            }
            if (cause instanceof ChatGroundingException groundingException) {
                throw groundingException;
            }
            if (cause instanceof Exception ordinaryException) {
                throw new ChatDependencyException(ordinaryException);
            }
            throw new IllegalStateException("Unexpected worker failure type", cause);
        }
    }
}
