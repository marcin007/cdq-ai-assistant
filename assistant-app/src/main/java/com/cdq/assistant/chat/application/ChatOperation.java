package com.cdq.assistant.chat.application;

@FunctionalInterface
public interface ChatOperation {

    ChatResult chat(String message);
}
