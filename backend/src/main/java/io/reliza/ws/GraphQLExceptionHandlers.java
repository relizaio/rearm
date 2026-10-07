/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

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
import io.reliza.exceptions.RelizaException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;

@ControllerAdvice
@Component
public class GraphQLExceptionHandlers {
    private static final Logger log = LoggerFactory.getLogger(GraphQLExceptionHandlers.class);

    private GraphQLError safeError(String message) {
        return GraphqlErrorBuilder.newError().message(message).build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleReliza(RelizaException ex) {
        // Business error messages are considered safe to expose
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
        log.error("Data access error", ex);
        return safeError("Database error");
    }

    @GraphQlExceptionHandler
    public GraphQLError handlePersistence(PersistenceException ex) {
        log.error("Persistence error", ex);
        return safeError("Database error");
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
        log.error("Unhandled server error", ex);
        return safeError("Internal server error");
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
        log.error("Unhandled server error", ex);
        return safeError("Internal server error");
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
        log.error("Unhandled server error", ex);
        return safeError("Internal server error");
    }
}
