package com.cdq.assistant.chat.application;

@FunctionalInterface
public interface ChatUseCase {

    ChatResult chat(String message);
}
