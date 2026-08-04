package com.cdq.assistant.chat.application;

public final class ChatGroundingException extends RuntimeException {
    public ChatGroundingException() {
        super("The assistant could not verify the answer with the required sources.");
    }
}
