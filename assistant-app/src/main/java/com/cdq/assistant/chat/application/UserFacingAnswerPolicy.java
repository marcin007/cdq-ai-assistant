package com.cdq.assistant.chat.application;

public final class UserFacingAnswerPolicy {

    private static final String THINK_START = "<think>";
    private static final String THINK_END = "</think>";

    public String release(String modelText) {
        if (modelText == null || modelText.isBlank()) {
            throw new ChatDependencyException();
        }

        String answer = modelText.strip();
        int lastThinkEnd = answer.lastIndexOf(THINK_END);
        if (lastThinkEnd >= 0) {
            answer = answer.substring(lastThinkEnd + THINK_END.length()).strip();
        }
        if (answer.isBlank()
                || answer.contains(THINK_START)
                || answer.contains(THINK_END)) {
            throw new ChatDependencyException();
        }
        return answer;
    }
}
