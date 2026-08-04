package com.cdq.assistant.rag;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;

import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdq.assistant.rag.refresh.CdqKnowledgeDiffer;
import com.cdq.assistant.rag.refresh.CdqKnowledgeProperties;
import com.cdq.assistant.rag.refresh.CdqKnowledgeVersionRepository;
import com.cdq.assistant.rag.refresh.CdqKnowledgeWorkflow;
import com.cdq.assistant.rag.refresh.CdqWebsiteClient;
import com.cdq.assistant.rag.refresh.CdqWebsiteContentExtractor;
import com.cdq.assistant.rag.refresh.HttpCdqWebsiteClient;
import com.cdq.assistant.rag.refresh.JdbcCdqKnowledgeVersionRepository;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CdqKnowledgeProperties.class)
@ConditionalOnProperty(name = "cdq.rag.enabled", havingValue = "true", matchIfMissing = true)
public class CdqRagConfiguration {

    @Bean
    CdqKnowledgeSource cdqKnowledgeSource() {
        return new CdqKnowledgeLoader(
                new ClassPathResource("knowledge/cdq-fraud-guard.txt"),
                new ClassPathResource("knowledge/cdq-fraud-guard.source.json"));
    }

    @Bean
    TransactionTemplate cdqKnowledgeTransactions(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    CdqVectorRepository cdqVectorRepository(
            PgVectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate cdqKnowledgeTransactions) {
        return new PgVectorCdqRepository(vectorStore, jdbcTemplate, cdqKnowledgeTransactions);
    }

    @Bean
    CdqKnowledgeIngestor cdqKnowledgeIngestor(CdqVectorRepository vectorRepository) {
        return new CdqKnowledgeIngestor(vectorRepository);
    }

    @Bean
    CdqKnowledgeSnapshotFactory cdqKnowledgeSnapshotFactory() {
        return new CdqKnowledgeSnapshotFactory();
    }

    @Bean
    CdqKnowledgeVersionRepository cdqKnowledgeVersionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
    }

    @Bean
    Clock cdqKnowledgeClock() {
        return Clock.systemUTC();
    }

    @Bean
    CdqWebsiteClient cdqWebsiteClient(
            CdqKnowledgeProperties properties,
            Environment environment,
            Clock cdqKnowledgeClock) {
        URI source = properties.validatedSource(
                environment.acceptsProfiles(Profiles.of("knowledge-test")));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new HttpCdqWebsiteClient(
                client,
                source,
                properties.requestTimeout(),
                properties.maxResponseBytes(),
                cdqKnowledgeClock);
    }

    @Bean
    CdqKnowledgeWorkflow cdqKnowledgeWorkflow(
            CdqKnowledgeProperties properties,
            Environment environment,
            CdqWebsiteClient websiteClient,
            CdqKnowledgeVersionRepository versionRepository,
            TransactionTemplate cdqKnowledgeTransactions,
            Clock cdqKnowledgeClock,
            CdqKnowledgeIngestor ingestor,
            CdqKnowledgeSnapshotFactory snapshotFactory) {
        URI source = properties.validatedSource(
                environment.acceptsProfiles(Profiles.of("knowledge-test")));
        return new CdqKnowledgeWorkflow(
                CdqKnowledgeLoader.SOURCE_ID,
                source,
                websiteClient,
                new CdqWebsiteContentExtractor(),
                versionRepository,
                new CdqKnowledgeDiffer(),
                cdqKnowledgeTransactions,
                cdqKnowledgeClock,
                ingestor,
                snapshotFactory);
    }

    @Bean
    CdqKnowledgeIngestionRunner cdqKnowledgeIngestionRunner(
            CdqKnowledgeWorkflow workflow,
            CdqKnowledgeSource knowledgeSource) {
        return new CdqKnowledgeIngestionRunner(workflow, knowledgeSource);
    }

    @Bean
    CdqFraudGuardSearchTool cdqFraudGuardSearchTool(CdqVectorRepository vectorRepository) {
        return new CdqFraudGuardSearchTool(vectorRepository);
    }
}
