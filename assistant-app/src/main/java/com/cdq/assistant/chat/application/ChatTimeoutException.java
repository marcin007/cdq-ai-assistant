package com.cdq.assistant.chat.application;

public final class ChatTimeoutException extends RuntimeException {

    public ChatTimeoutException() {
        super("The overall chat deadline expired.");
    }
}
