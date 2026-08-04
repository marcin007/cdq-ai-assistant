package com.cdq.assistant.chat.application;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class ChatServiceTest {

    @Test
    void cancelsTheWorkerAndReturnsATimeoutWithoutWaitingForTheProductionDeadline()
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ChatOperation blockingOperation = message -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("The blocking operation unexpectedly completed");
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new ChatDependencyException(exception);
            }
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatService service = new ChatService(blockingOperation, executor, Duration.ofMillis(25));

            assertThatThrownBy(() -> service.chat("Hello"))
                    .isInstanceOf(ChatTimeoutException.class);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void preservesAKnownDependencyFailureFromTheWorker() {
        ChatDependencyException failure = new ChatDependencyException();
        ChatOperation operation = message -> {
            throw failure;
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatService service = new ChatService(operation, executor, Duration.ofSeconds(1));

            assertThatThrownBy(() -> service.chat("Hello")).isSameAs(failure);
        }
    }

    @Test
    void preservesTheExactTimeoutFailureFromTheWorker() {
        ChatTimeoutException failure = new ChatTimeoutException();
        ChatOperation operation = message -> {
            throw failure;
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatService service = new ChatService(operation, executor, Duration.ofSeconds(1));

            assertThatThrownBy(() -> service.chat("Hello")).isSameAs(failure);
        }
    }

    @Test
    void preservesTheExactGroundingFailureFromTheWorker() {
        ChatGroundingException failure = new ChatGroundingException();
        ChatOperation operation = message -> {
            throw failure;
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatService service = new ChatService(operation, executor, Duration.ofSeconds(1));

            assertThatThrownBy(() -> service.chat("What is Germany's capital?"))
                    .isSameAs(failure);
        }
    }

    @Test
    void rethrowsTheExactErrorFromTheWorker() {
        AssertionError failure = new AssertionError("fatal worker failure");
        ChatOperation operation = message -> {
            throw failure;
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatService service = new ChatService(operation, executor, Duration.ofSeconds(1));

            assertThatThrownBy(() -> service.chat("Hello")).isSameAs(failure);
        }
    }

    @Test
    void wrapsAnOrdinaryWorkerExceptionAndPreservesItAsTheCause() {
        IllegalStateException failure = new IllegalStateException("ordinary worker failure");
        ChatOperation operation = message -> {
            throw failure;
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatService service = new ChatService(operation, executor, Duration.ofSeconds(1));

            Throwable thrown = catchThrowable(() -> service.chat("Hello"));
            assertThat(thrown).isInstanceOf(ChatDependencyException.class);
            assertThat(thrown.getCause()).isSameAs(failure);
        }
    }

    @Test
    void cancelsTheWorkerAndRestoresTheCallerInterruptFlagWhenTheCallerIsInterrupted()
            throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        CompletableFuture<Throwable> callerOutcome = new CompletableFuture<>();
        ChatOperation blockingOperation = message -> {
            workerStarted.countDown();
            try {
                releaseWorker.await();
                return new ChatResult("unexpected", List.of());
            } catch (InterruptedException exception) {
                workerInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new ChatDependencyException(exception);
            }
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatService service =
                    new ChatService(blockingOperation, executor, Duration.ofSeconds(5));
            Thread caller = Thread.ofVirtual().name("interrupted-chat-caller").start(() -> {
                try {
                    service.chat("Hello");
                    callerOutcome.complete(
                            new AssertionError("The interrupted chat call unexpectedly completed"));
                } catch (Throwable throwable) {
                    callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                    callerOutcome.complete(throwable);
                }
            });

            try {
                assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
                caller.interrupt();

                Throwable thrown = callerOutcome.get(1, TimeUnit.SECONDS);
                assertThat(thrown).isInstanceOf(ChatDependencyException.class);
                assertThat(thrown.getCause()).isInstanceOf(InterruptedException.class);
                assertThat(callerInterruptRestored).isTrue();
                assertThat(workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
                caller.join(1_000);
                assertThat(caller.isAlive()).isFalse();
            } finally {
                releaseWorker.countDown();
                caller.interrupt();
                caller.join(1_000);
            }
        }
    }

    @Test
    void returnsTheCompletedOrchestrationResult() {
        ChatResult expected = new ChatResult("Answer", List.of());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatService service =
                    new ChatService(message -> expected, executor, Duration.ofSeconds(1));

            assertThat(service.chat("Hello")).isSameAs(expected);
        }
    }
}
