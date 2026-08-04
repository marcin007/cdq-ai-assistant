package com.cdq.assistant.chat.application;

public class ChatDependencyException extends RuntimeException {

    public ChatDependencyException() {
        super("A required chat dependency failed.");
    }

    ChatDependencyException(Throwable cause) {
        super("A required chat dependency failed.", cause);
    }
}
