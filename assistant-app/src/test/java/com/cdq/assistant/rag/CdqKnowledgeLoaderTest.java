package com.cdq.assistant.rag;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static java.security.MessageDigest.getInstance;

class CdqKnowledgeLoaderTest {

    private static final String SNAPSHOT_HASH =
            "35fe98e4df21b5811132758f3aa805b704b8ba948d9fe6384d30cfaf0b6f30cc";

    @Test
    void createsAChunkedSnapshotFromStoredUtf8Content() throws Exception {
        byte[] content = ("CDQ Fraud Guard\n\n" + "Verified product content. ".repeat(20) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(getInstance("SHA-256").digest(content));

        CdqKnowledgeSnapshot snapshot = new CdqKnowledgeSnapshotFactory().create(
                "https://www.cdq.com/products/cdq-fraud-guard",
                "2026-08-04T09:00:00Z",
                hash,
                content);

        assertThat(snapshot.snapshotHash()).isEqualTo(hash);
        assertThat(snapshot.content()).isEqualTo(new String(content, StandardCharsets.UTF_8));
        assertThat(snapshot.chunks()).isNotEmpty();
        assertThat(snapshot.chunks().getFirst().getMetadata())
                .containsEntry("sourceId", "cdq-fraud-guard")
                .containsEntry("capturedAt", "2026-08-04T09:00:00Z")
                .containsEntry("chunkIndex", 0);
    }

    @Test
    void loadsCanonicalSnapshotWithConfiguredChunksAndRequiredMetadata() {
        CdqKnowledgeLoader loader = new CdqKnowledgeLoader(
                new ClassPathResource("knowledge/cdq-fraud-guard.txt"),
                new ClassPathResource("knowledge/cdq-fraud-guard.source.json"));

        CdqKnowledgeSnapshot snapshot = loader.load();

        assertThat(snapshot.sourceId()).isEqualTo("cdq-fraud-guard");
        assertThat(snapshot.sourceUrl()).isEqualTo("https://www.cdq.com/products/cdq-fraud-guard");
        assertThat(snapshot.capturedAt()).isEqualTo("2026-07-26T08:22:11Z");
        assertThat(snapshot.snapshotHash()).isEqualTo(SNAPSHOT_HASH);
        assertThat(snapshot.chunks())
                .extracting(Document::getText)
                .containsExactly(
                        """
                        CDQ Fraud Guard

                        Protect Your Business, Secure Your Transactions

                        In today's digital economy, businesses face increasing threats from payment fraud and inaccurate bank account information. CDQ Fraud Guard offers a robust service to manage and verify global payment data, providing peace of mind that your transactions are secure and compliant.

                        Combat Payment Fraud Collaboratively with the CDQ Fraud Guard

                        Fraudulent activities such as falsified invoices can cause immense financial damage. Identifying bank accounts that do not belong to the declared business partner but to an attacker is a critical challenge. CDQ Fraud Guard leverages the power of community-shared data on proven bank accounts and known fraud cases to help you identify and prevent fraud, ensuring the integrity of your payment processes.

                        Protect Your Business from Payment Fraud

                        CDQ Fraud Guard integrates advanced verification and alert systems to protect your business from payment fraud. By utilizing a shared database of verified bank accounts and known fraud cases, CDQ Fraud Guard ensures your financial transactions are secure and reliable, keeping your business safe.

                        At a glance

                        Key Features of CDQ Fraud Guard

                        - Bank Account Verification: Utilizes a shared database of validated bank accounts to verify new bank account information before operational use.
                        - Trust Score: Assigns a trust score to bank accounts based on the number of successful transactions, customizable to fit your risk appetite.
                        - Payment Fraud Alerts: Warns community members about potential fraud attacks by maintaining and sharing known fraud cases.""",
                        """
                        - Fraud Case Management: Allows users to document, manage, and look up fraud cases to identify critical accounts and prevent fraud.
                        - Seamless Integration: Easily integrates with existing financial systems through a robust API, enhancing workflow efficiencies without requiring a dedicated interface.

                        CDQ Fraud Guard Highlights

                        Enhanced Security

                        Reduce the risk of fraud by verifying bank account data against a shared database of validated accounts and known fraud cases.

                        Operational Efficiency

                        Streamline the process of verifying bank account data and managing fraud cases, saving time and resources.

                        Customizable Trust Scores

                        Allows customization of trust scores based on the number of transactions, ensuring the system aligns with your specific risk management needs.

                        Community-Driven Data

                        Leverages the power of the CDQ Data Sharing community to provide reliable and up-to-date information on bank accounts and fraud cases.

                        Real-Time Fraud Alerts

                        Provides immediate alerts on potential fraud cases, enabling quick response and prevention.

                        CDQ Fraud Guard in Action

                        Before implementing CDQ Trust Score, documentation was piling up and business partner onboarding took up to one month. Now, the green or yellow light as a trust score lets us move ahead with no additional documentation and efforts.

                        Arnab Kundu
                        Global Process Expert MDM at Clariant
                        """.stripTrailing());

        assertThat(snapshot.chunks()).hasSize(2);
        for (int index = 0; index < snapshot.chunks().size(); index++) {
            assertRequiredMetadata(snapshot.chunks().get(index), index);
        }
    }

    @Test
    void rejectsProvenanceWhenItsHashDoesNotMatchTheSnapshotBytes() {
        CdqKnowledgeLoader loader = new CdqKnowledgeLoader(
                new ByteArrayResource("sample\n".getBytes()),
                new ByteArrayResource("""
                        {
                          "sourceUrl": "https://example.test/product",
                          "capturedAt": "2026-07-26T08:22:11Z",
                          "snapshotHash": "0000000000000000000000000000000000000000000000000000000000000000"
                        }
                        """.getBytes()));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Snapshot SHA-256");
    }

    private static void assertRequiredMetadata(Document document, int chunkIndex) {
        assertThat(document.getMetadata())
                .containsEntry("sourceId", "cdq-fraud-guard")
                .containsEntry("sourceUrl", "https://www.cdq.com/products/cdq-fraud-guard")
                .containsEntry("capturedAt", "2026-07-26T08:22:11Z")
                .containsEntry("snapshotHash", SNAPSHOT_HASH)
                .containsEntry("chunkIndex", chunkIndex);
        assertThat(document.getMetadata().get("chunkIndex")).isInstanceOf(Integer.class);
    }
}
