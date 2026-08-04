package com.cdq.assistant.rag;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import com.cdq.assistant.rag.refresh.CdqKnowledgeWorkflow;

public final class CdqKnowledgeIngestionRunner implements ApplicationRunner {

    private final CdqKnowledgeWorkflow workflow;
    private final CdqKnowledgeSource knowledgeSource;

    public CdqKnowledgeIngestionRunner(
            CdqKnowledgeWorkflow workflow,
            CdqKnowledgeSource knowledgeSource) {
        this.workflow = workflow;
        this.knowledgeSource = knowledgeSource;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        workflow.initializeFrom(knowledgeSource);
    }
}
