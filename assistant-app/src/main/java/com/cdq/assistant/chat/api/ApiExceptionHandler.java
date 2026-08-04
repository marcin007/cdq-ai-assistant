package com.cdq.assistant.chat.api;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.cdq.assistant.chat.application.ChatDependencyException;
import com.cdq.assistant.chat.application.ChatGroundingException;
import com.cdq.assistant.chat.application.ChatTimeoutException;
import com.cdq.assistant.rag.refresh.CdqKnowledgeFailureCode;
import com.cdq.assistant.rag.refresh.CdqKnowledgeOperationException;

@RestControllerAdvice
public final class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String INVALID_DETAIL =
            "The request body must contain a message of 1 to 2000 characters.";
    private static final String DEPENDENCY_DETAIL =
            "The assistant could not complete the request because a required dependency failed.";
    private static final String GROUNDING_DETAIL =
            "The assistant could not verify the answer with the required sources.";
    private static final String TIMEOUT_DETAIL =
            "The assistant did not complete the request within 45 seconds.";
    private static final String KNOWLEDGE_INVALID_DETAIL =
            "The approval comment must contain at most 500 characters.";

    @ExceptionHandler(ChatDependencyException.class)
    ResponseEntity<ProblemDetail> handleDependencyFailure(
            ChatDependencyException exception, WebRequest request) {
        return response(
                problem(HttpStatus.SERVICE_UNAVAILABLE, "Dependency unavailable", DEPENDENCY_DETAIL, request));
    }

    @ExceptionHandler(ChatGroundingException.class)
    ResponseEntity<ProblemDetail> handleGroundingFailure(
            ChatGroundingException exception, WebRequest request) {
        return response(problem(
                HttpStatus.SERVICE_UNAVAILABLE, "Answer not verified", GROUNDING_DETAIL, request));
    }

    @ExceptionHandler(ChatTimeoutException.class)
    ResponseEntity<ProblemDetail> handleTimeout(
            ChatTimeoutException exception, WebRequest request) {
        return response(problem(HttpStatus.GATEWAY_TIMEOUT, "Request timed out", TIMEOUT_DETAIL, request));
    }

    @ExceptionHandler(CdqKnowledgeOperationException.class)
    ResponseEntity<ProblemDetail> handleKnowledgeOperation(
            CdqKnowledgeOperationException exception, WebRequest request) {
        KnowledgeFailure failure = KnowledgeFailure.from(exception.code());
        ProblemDetail problem = problem(failure.status(), failure.title(), failure.detail(), request);
        problem.setProperty("code", exception.code().name());
        return response(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return invalid(exception, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return invalid(exception, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return invalid(exception, headers, request);
    }

    private ResponseEntity<Object> invalid(
            Exception exception, HttpHeaders headers, WebRequest request) {
        ProblemDetail body =
                problem(HttpStatus.BAD_REQUEST, "Invalid request", invalidDetail(request), request);
        return handleExceptionInternal(exception, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static String invalidDetail(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            String requestUri = servletWebRequest.getRequest().getRequestURI();
            if (requestUri.equals("/api/knowledge/cdq")
                    || requestUri.startsWith("/api/knowledge/cdq/")) {
                return KNOWLEDGE_INVALID_DETAIL;
            }
        }
        return INVALID_DETAIL;
    }

    private static ProblemDetail problem(
            HttpStatus status, String title, String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(title);
        if (request instanceof ServletWebRequest servletWebRequest) {
            problem.setInstance(URI.create(servletWebRequest.getRequest().getRequestURI()));
        }
        return problem;
    }

    private static ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private record KnowledgeFailure(HttpStatus status, String title, String detail) {

        private static KnowledgeFailure from(CdqKnowledgeFailureCode code) {
            return switch (code) {
                case SOURCE_UNAVAILABLE -> new KnowledgeFailure(
                        HttpStatus.BAD_GATEWAY,
                        "CDQ source unavailable",
                        "The configured CDQ knowledge source could not be reached.");
                case SOURCE_TIMEOUT -> new KnowledgeFailure(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "CDQ source timed out",
                        "The configured CDQ knowledge source did not respond in time.");
                case SOURCE_RESPONSE_INVALID, SOURCE_CONTENT_INVALID -> new KnowledgeFailure(
                        HttpStatus.BAD_GATEWAY,
                        "CDQ source response invalid",
                        "The configured CDQ knowledge source returned an invalid response.");
                case VERSION_NOT_FOUND -> new KnowledgeFailure(
                        HttpStatus.NOT_FOUND,
                        "Knowledge version not found",
                        "The requested CDQ knowledge version does not exist.");
                case VERSION_STATE_CONFLICT -> new KnowledgeFailure(
                        HttpStatus.CONFLICT,
                        "Knowledge version state conflict",
                        "The requested operation is not available for this CDQ knowledge version.");
                case INGEST_UNAVAILABLE -> new KnowledgeFailure(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Knowledge ingest unavailable",
                        "The approved CDQ knowledge version could not be ingested.");
            };
        }
    }
}
