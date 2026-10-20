/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;

import graphql.ErrorClassification;
import graphql.GraphqlErrorBuilder;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;

import com.netflix.graphql.dgs.exceptions.DgsEntityNotFoundException;

import io.reliza.exceptions.ActionRefusedException;
import io.reliza.exceptions.ComponentRevisionConflictException;
import io.reliza.exceptions.InvalidInputException;
import io.reliza.exceptions.RebomResponseTooLargeException;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.SbomScoreException;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.service.RebomService;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;

@ControllerAdvice
@Component
public class GraphQLExceptionHandlers {
    private static final Logger log = LoggerFactory.getLogger(GraphQLExceptionHandlers.class);

    static final String EXPORT_TOO_LARGE_MESSAGE =
            "Release SBOM export is too large to serve (limit " + RebomService.REBOM_RESPONSE_LIMIT_MB
            + " MB); export with fewer components (top-level only)";

    private GraphQLError safeError(String message) {
        return GraphqlErrorBuilder.newError().message(message).build();
    }

    /**
     * A server-side fault: the client gets the generic message plus a short reference, and the
     * full exception is logged under the same reference. The client still learns nothing about
     * the cause (no exception text reaches it), but a user can quote "ref 3f9a1c2e" and the log
     * line is one search away. Before, "Internal server error" could only be matched to its
     * stack by timestamp.
     */
    private GraphQLError serverError(String message, String logMessage, Throwable ex) {
        String ref = newErrorRef();
        log.error("{} [ref {}]", logMessage, ref, ex);
        return GraphqlErrorBuilder.newError()
                .message(message + " (ref " + ref + ")")
                .extensions(java.util.Map.of("ref", ref))
                .build();
    }

    /** 8 hex characters: enough to find one line in a day of logs, short enough to read out. */
    static String newErrorRef() {
        return String.format("%08x", java.util.concurrent.ThreadLocalRandom.current().nextInt());
    }

    @GraphQlExceptionHandler
    public GraphQLError handleReliza(RelizaException ex) {
        // Business error messages are considered safe to expose
        return GraphqlErrorBuilder.newError()
                .message(ex.getMessage())
                .errorType(ErrorClassification.errorClassification("BAD_REQUEST"))
                .build();
    }

    /**
     * A resolver's own argument that does not fit its type (task TPF-63), refused by
     * {@code InputConversion}: the message names the field and what it must be, never the value
     * sent. A client error, not a fault: no log line and no ref.
     */
    @GraphQlExceptionHandler
    public GraphQLError handleInvalidInput(InvalidInputException ex) {
        return GraphqlErrorBuilder.newError()
                .message(ex.getMessage())
                .errorType(ErrorClassification.errorClassification("BAD_REQUEST"))
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleActionRefused(ActionRefusedException ex) {
        // A policy the org configured said no. Same treatment as RelizaException: the message
        // names the guard that refused and is the whole point of the refusal.
        return GraphqlErrorBuilder.newError()
                .message(ex.getMessage())
                .errorType(ErrorClassification.errorClassification("BAD_REQUEST"))
                .build();
    }

    /**
     * An answer from rebom too large to read: on an export, the document cannot be served from this
     * buffer, and the user can only make it smaller. The score path never gets here: it answers
     * {@code INPUT_TOO_LARGE} itself.
     */
    @GraphQlExceptionHandler
    public GraphQLError handleRebomResponseTooLarge(RebomResponseTooLargeException ex) {
        return GraphqlErrorBuilder.newError()
                .message(EXPORT_TOO_LARGE_MESSAGE)
                .errorType(ErrorClassification.errorClassification("BAD_REQUEST"))
                .build();
    }

    /**
     * A score that could not be made. With {@link #handleComponentRevisionConflict} one of the two
     * handlers that set a {@code code} extension (the server-error handlers set only {@code ref}):
     * the caller decides on {@code extensions.reason}, and the message is shown to a person as it is.
     */
    @GraphQlExceptionHandler
    public GraphQLError handleSbomScore(SbomScoreException ex) {
        return GraphqlErrorBuilder.newError()
                .message(ex.getMessage())
                .errorType(ErrorClassification.errorClassification("BAD_REQUEST"))
                .extensions(Map.of("code", SbomScoreException.ERROR_CODE, "reason", ex.getReason().name()))
                .build();
    }

    /**
     * A component settings save prepared from an older revision (TPF-61): nothing was written. A
     * client switches on {@code extensions.code = COMPONENT_REVISION_CONFLICT}, never on the message,
     * and reads the current revision and who changed the component last, and when, from the other
     * extensions. BAD_REQUEST like every other domain refusal; the message stays for clients that do
     * not know the code. More specific than {@link #handleReliza}, so it wins for this subclass.
     * Nothing is logged: a refusal is an ordinary outcome.
     */
    @GraphQlExceptionHandler
    public GraphQLError handleComponentRevisionConflict(ComponentRevisionConflictException ex) {
        return GraphqlErrorBuilder.newError()
                .message(ex.getMessage())
                .errorType(ErrorClassification.errorClassification("BAD_REQUEST"))
                .extensions(revisionConflictExtensions(ex))
                .build();
    }

    /**
     * The refusal's extensions in a fixed order; a value that is unknown is left out rather than
     * sent as null ({@code code}, {@code revision} and {@code lastUpdatedDate} are always present).
     */
    static Map<String, Object> revisionConflictExtensions(ComponentRevisionConflictException ex) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("code", ComponentRevisionConflictException.ERROR_CODE);
        extensions.put("revision", ex.getCurrentRevision());
        if (null != ex.getLastUpdatedDate()) {
            extensions.put("lastUpdatedDate", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ex.getLastUpdatedDate()));
        }
        if (null != ex.getLastUpdatedBy()) extensions.put("lastUpdatedBy", ex.getLastUpdatedBy().toString());
        if (null != ex.getLastUpdatedType()) extensions.put("lastUpdatedType", ex.getLastUpdatedType().name());
        if (null != ex.getLastUpdatedActor()) extensions.put("lastUpdatedActor", ex.getLastUpdatedActor().toString());
        return extensions;
    }

    /**
     * An SBOM upload refused by rule B (SCORE-52 ADR-4): a new version of an existing serialNumber
     * without org-wide write or write on every holder. The message names the serialNumber as sent
     * only; {@code extensions} carry the code and nothing naming a holder.
     */
    @GraphQlExceptionHandler
    public GraphQLError handleSbomUpdateNotPermitted(SbomUpdateNotPermittedException ex) {
        return GraphqlErrorBuilder.newError()
                .message(ex.getMessage())
                .errorType(ErrorClassification.errorClassification("BAD_REQUEST"))
                .extensions(Map.of("code", SbomUpdateNotPermittedException.ERROR_CODE))
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleAccessDenied(AccessDeniedException ex) {
        // The client only ever sees "Not authorized"; keep the real reason
        // (org mismatch, key missing permissions, secret not distributed,
        // sealed cert missing, ...) in the server log so it can be diagnosed.
        log.error("Access denied: {}", ex.getMessage(), ex);
        return safeError("Not authorized");
    }

    @GraphQlExceptionHandler
    public GraphQLError handleNotFound(DgsEntityNotFoundException ex) {
        // Lookup misses ("Instance not found", "Wrong deliverable") are
        // business outcomes, not server faults: surface the message with a
        // NOT_FOUND classification instead of a logged 500.
        return GraphqlErrorBuilder.newError()
                .message(ex.getMessage())
                .errorType(ErrorClassification.errorClassification("NOT_FOUND"))
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return safeError("Request violates data constraints");
    }

    @GraphQlExceptionHandler
    public GraphQLError handleDataAccess(DataAccessException ex) {
        return serverError("Database error", "Data access error", ex);
    }

    @GraphQlExceptionHandler
    public GraphQLError handlePersistence(PersistenceException ex) {
        return serverError("Database error", "Persistence error", ex);
    }

    @GraphQlExceptionHandler
    public GraphQLError handleLockTimeout(LockTimeoutException ex) {
        log.warn("Lock timeout while accessing resource", ex);
        return safeError("Resource is busy, please retry");
    }

    @GraphQlExceptionHandler
    public GraphQLError handlePessimistic(PessimisticLockException ex) {
        log.warn("Pessimistic lock acquisition failed", ex);
        return safeError("Resource is busy, please retry");
    }

    /**
     * An argument that is not a uuid where one goes -- a board's name given to board show, say (task
     * RD3-3). DGS converts an ID argument declared as UUID with Spring's converter, which throws this
     * before the fetcher runs; it used to surface as "Internal server error". Now it names the
     * argument: "boardUuid: not a uuid". Any other failed conversion stays a server error.
     */
    @GraphQlExceptionHandler
    public GraphQLError handleConversion(ConversionFailedException ex, DataFetchingEnvironment env) {
        if (null != ex.getTargetType() && java.util.UUID.class.equals(ex.getTargetType().getType())) {
            return notAUuid(env, ex.getValue());
        }
        return serverError("Internal server error", "Unhandled server error", ex);
    }

    /**
     * The same refusal when a fetcher takes the uuid as a string and parses it itself: UUID.fromString's
     * IllegalArgumentException. Any other IllegalArgumentException stays a server error.
     */
    @GraphQlExceptionHandler
    public GraphQLError handleIllegalArgument(IllegalArgumentException ex, DataFetchingEnvironment env) {
        String m = ex.getMessage();
        if (null != m && (m.startsWith(INVALID_UUID) || m.startsWith("UUID string too large"))) {
            return notAUuid(env, m.startsWith(INVALID_UUID) ? m.substring(INVALID_UUID.length()) : null);
        }
        return serverError("Internal server error", "Unhandled server error", ex);
    }

    private static final String INVALID_UUID = "Invalid UUID string: ";

    /** "boardUuid: not a uuid", the argument found by its value; "input.board: …" inside an input object. */
    private GraphQLError notAUuid(DataFetchingEnvironment env, Object value) {
        String where = null == env || null == value ? null : argumentHolding(env.getArguments(), value, "");
        return GraphqlErrorBuilder.newError()
                .message((null == where ? "an argument" : where) + ": not a uuid")
                .errorType(ErrorClassification.errorClassification("BAD_REQUEST"))
                .build();
    }

    private static String argumentHolding(java.util.Map<String, Object> args, Object value, String prefix) {
        if (null == args) return null;
        for (java.util.Map.Entry<String, Object> e : args.entrySet()) {
            Object v = e.getValue();
            if (value.equals(v)) return prefix + e.getKey();
            if (v instanceof java.util.Collection<?> c && c.contains(value)) return prefix + e.getKey();
            if (v instanceof java.util.Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                String inner = argumentHolding((java.util.Map<String, Object>) m, value, prefix + e.getKey() + ".");
                if (null != inner) return inner;
            }
        }
        return null;
    }

    @GraphQlExceptionHandler
    public GraphQLError handleGeneric(Exception ex) {
        return serverError("Internal server error", "Unhandled server error", ex);
    }
}
