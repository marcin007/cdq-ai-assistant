package com.cdq.assistant.rag.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cdq.assistant.chat.api.ApiExceptionHandler;
import com.cdq.assistant.rag.refresh.CdqKnowledgeDiff;
import com.cdq.assistant.rag.refresh.CdqKnowledgeDiffLine;
import com.cdq.assistant.rag.refresh.CdqKnowledgeFailureCode;
import com.cdq.assistant.rag.refresh.CdqKnowledgeOperationException;
import com.cdq.assistant.rag.refresh.CdqKnowledgeScan;
import com.cdq.assistant.rag.refresh.CdqKnowledgeScanOutcome;
import com.cdq.assistant.rag.refresh.CdqKnowledgeUseCase;
import com.cdq.assistant.rag.refresh.CdqKnowledgeVersion;
import com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus;
import com.cdq.assistant.rag.refresh.CdqKnowledgeView;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CdqKnowledgeControllerTest {

    private static final URI CANONICAL_URL = URI.create("https://www.cdq.com/products/cdq-fraud-guard");
    private static final UUID ACTIVE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CANDIDATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-04T10:15:30Z");
    private static final Instant REVIEWED_AT = Instant.parse("2026-08-04T10:30:00Z");
    private static final Instant ACTIVATED_AT = Instant.parse("2026-08-04T10:45:00Z");
    private static final Instant SCANNED_AT = Instant.parse("2026-08-04T10:00:00Z");

    private StubCdqKnowledgeUseCase useCase;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        useCase = new StubCdqKnowledgeUseCase();
        mvc = MockMvcBuilders.standaloneSetup(new CdqKnowledgeController(useCase))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void unchangedScanReturnsNoCandidateAndNoAvailableActions() throws Exception {
        useCase.next = unchangedView();

        mvc.perform(post("/api/knowledge/cdq/scan"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sourceUrl").value(CANONICAL_URL.toString()))
                .andExpect(jsonPath("$.lastScan.outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.candidate").doesNotExist())
                .andExpect(jsonPath("$.actions.canReject").value(false))
                .andExpect(jsonPath("$.actions.canApprove").value(false))
                .andExpect(jsonPath("$.actions.canIngest").value(false));
    }

    @Test
    void statusReturnsOnlyVersionSummariesAndTheTypedCandidateDiff() throws Exception {
        useCase.next = changedView();

        mvc.perform(get("/api/knowledge/cdq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active.id").value(ACTIVE_ID.toString()))
                .andExpect(jsonPath("$.active.snapshotHash").value("active-hash"))
                .andExpect(jsonPath("$.active.capturedAt").value(CAPTURED_AT.toString()))
                .andExpect(jsonPath("$.active.activatedAt").value(ACTIVATED_AT.toString()))
                .andExpect(jsonPath("$.candidate.id").value(CANDIDATE_ID.toString()))
                .andExpect(jsonPath("$.candidate.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.candidate.snapshotHash").value("candidate-hash"))
                .andExpect(jsonPath("$.candidate.diff.addedLines").value(1))
                .andExpect(jsonPath("$.candidate.diff.removedLines").value(1))
                .andExpect(jsonPath("$.candidate.diff.lines[0].type").value("REMOVED"))
                .andExpect(jsonPath("$.candidate.diff.lines[0].text").value("Old public fact"))
                .andExpect(jsonPath("$.candidate.diff.lines[1].type").value("ADDED"))
                .andExpect(jsonPath("$.candidate.diff.lines[1].text").value("New public fact"))
                .andExpect(jsonPath("$.candidate.content").doesNotExist())
                .andExpect(jsonPath("$.active.content").doesNotExist())
                .andExpect(jsonPath("$.actions.canApprove").value(true));
    }

    @Test
    void approveTrimsAnOptionalComment() throws Exception {
        useCase.next = changedView();

        mvc.perform(post("/api/knowledge/cdq/versions/{id}/approve", CANDIDATE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"  approved after review  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.reviewComment").value("ready"));

        org.assertj.core.api.Assertions.assertThat(useCase.approvedId).isEqualTo(CANDIDATE_ID);
        org.assertj.core.api.Assertions.assertThat(useCase.approvedComment).isEqualTo("approved after review");
    }

    @Test
    void rejectReturnsTheUpdatedReviewState() throws Exception {
        useCase.next = changedView();

        mvc.perform(post("/api/knowledge/cdq/versions/{id}/reject", CANDIDATE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.status").value("PENDING_REVIEW"));

        org.assertj.core.api.Assertions.assertThat(useCase.rejectedId).isEqualTo(CANDIDATE_ID);
    }

    @Test
    void ingestReturnsTheUpdatedActiveSummary() throws Exception {
        useCase.next = changedView();

        mvc.perform(post("/api/knowledge/cdq/versions/{id}/ingest", CANDIDATE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active.id").value(ACTIVE_ID.toString()));

        org.assertj.core.api.Assertions.assertThat(useCase.ingestedId).isEqualTo(CANDIDATE_ID);
    }

    @Test
    void approvalCommentOver500CharactersReturnsTheKnowledgeValidationProblem() throws Exception {
        mvc.perform(post("/api/knowledge/cdq/versions/{id}/approve", CANDIDATE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail")
                        .value("The approval comment must contain at most 500 characters."))
                .andExpect(jsonPath("$.instance").value("/api/knowledge/cdq/versions/" + CANDIDATE_ID + "/approve"));
    }

    @Test
    void versionNotFoundReturnsASafeProblem() throws Exception {
        useCase.failure = new CdqKnowledgeOperationException(CdqKnowledgeFailureCode.VERSION_NOT_FOUND);

        assertProblem(post("/api/knowledge/cdq/versions/{id}/reject", CANDIDATE_ID), 404,
                "Knowledge version not found",
                "The requested CDQ knowledge version does not exist.",
                "VERSION_NOT_FOUND");
    }

    @Test
    void versionStateConflictReturnsASafeProblem() throws Exception {
        useCase.failure = new CdqKnowledgeOperationException(CdqKnowledgeFailureCode.VERSION_STATE_CONFLICT);

        assertProblem(post("/api/knowledge/cdq/versions/{id}/approve", CANDIDATE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                409,
                "Knowledge version state conflict",
                "The requested operation is not available for this CDQ knowledge version.",
                "VERSION_STATE_CONFLICT");
    }

    @Test
    void sourceFailureReturnsASafeBadGatewayProblem() throws Exception {
        useCase.failure = new CdqKnowledgeOperationException(CdqKnowledgeFailureCode.SOURCE_UNAVAILABLE);

        assertProblem(post("/api/knowledge/cdq/scan"), 502,
                "CDQ source unavailable",
                "The configured CDQ knowledge source could not be reached.",
                "SOURCE_UNAVAILABLE");
    }

    @Test
    void sourceTimeoutReturnsASafeGatewayTimeoutProblem() throws Exception {
        useCase.failure = new CdqKnowledgeOperationException(CdqKnowledgeFailureCode.SOURCE_TIMEOUT);

        assertProblem(post("/api/knowledge/cdq/scan"), 504,
                "CDQ source timed out",
                "The configured CDQ knowledge source did not respond in time.",
                "SOURCE_TIMEOUT");
    }

    @Test
    void ingestFailureReturnsASafeServiceUnavailableProblem() throws Exception {
        useCase.failure = new CdqKnowledgeOperationException(CdqKnowledgeFailureCode.INGEST_UNAVAILABLE);

        assertProblem(post("/api/knowledge/cdq/versions/{id}/ingest", CANDIDATE_ID), 503,
                "Knowledge ingest unavailable",
                "The approved CDQ knowledge version could not be ingested.",
                "INGEST_UNAVAILABLE");
    }

    private void assertProblem(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            int expectedStatus,
            String expectedTitle,
            String expectedDetail,
            String expectedCode) throws Exception {
        mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value(expectedTitle))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.detail").value(expectedDetail))
                .andExpect(jsonPath("$.instance").exists())
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(content().string(not(containsString("provider-secret"))));
    }

    private static CdqKnowledgeView unchangedView() {
        return new CdqKnowledgeView(
                CANONICAL_URL,
                activeVersion(),
                new CdqKnowledgeScan(null, "cdq-fraud-guard", SCANNED_AT,
                        CdqKnowledgeScanOutcome.UNCHANGED, "active-hash", null, null),
                null,
                null,
                false,
                false,
                false);
    }

    private static CdqKnowledgeView changedView() {
        return new CdqKnowledgeView(
                CANONICAL_URL,
                activeVersion(),
                new CdqKnowledgeScan(null, "cdq-fraud-guard", SCANNED_AT,
                        CdqKnowledgeScanOutcome.CHANGES_DETECTED, "candidate-hash", CANDIDATE_ID, null),
                candidateVersion(),
                new CdqKnowledgeDiff(1, 1, List.of(
                        new CdqKnowledgeDiffLine(CdqKnowledgeDiffLine.Type.REMOVED, "Old public fact"),
                        new CdqKnowledgeDiffLine(CdqKnowledgeDiffLine.Type.ADDED, "New public fact"))),
                true,
                true,
                false);
    }

    private static CdqKnowledgeVersion activeVersion() {
        return new CdqKnowledgeVersion(ACTIVE_ID, "cdq-fraud-guard", CANONICAL_URL,
                CdqKnowledgeVersionStatus.ACTIVE, "active internal content", "active-hash", CAPTURED_AT,
                CAPTURED_AT, REVIEWED_AT, "activated", ACTIVATED_AT, null);
    }

    private static CdqKnowledgeVersion candidateVersion() {
        return new CdqKnowledgeVersion(CANDIDATE_ID, "cdq-fraud-guard", CANONICAL_URL,
                CdqKnowledgeVersionStatus.PENDING_REVIEW, "candidate internal content", "candidate-hash",
                CAPTURED_AT, CAPTURED_AT, REVIEWED_AT, "ready", null, null);
    }

    private static final class StubCdqKnowledgeUseCase implements CdqKnowledgeUseCase {

        private CdqKnowledgeView next = unchangedView();
        private RuntimeException failure;
        private UUID approvedId;
        private String approvedComment;
        private UUID rejectedId;
        private UUID ingestedId;

        @Override
        public CdqKnowledgeView status() {
            return result();
        }

        @Override
        public CdqKnowledgeView scan() {
            return result();
        }

        @Override
        public CdqKnowledgeView approve(UUID versionId, String comment) {
            approvedId = versionId;
            approvedComment = comment;
            return result();
        }

        @Override
        public CdqKnowledgeView reject(UUID versionId) {
            rejectedId = versionId;
            return result();
        }

        @Override
        public CdqKnowledgeView ingest(UUID versionId) {
            ingestedId = versionId;
            return result();
        }

        private CdqKnowledgeView result() {
            if (failure != null) {
                throw failure;
            }
            return next;
        }
    }
}
