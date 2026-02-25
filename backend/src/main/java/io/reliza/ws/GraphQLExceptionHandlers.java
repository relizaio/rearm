/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;

import graphql.ErrorClassification;
import graphql.GraphqlErrorBuilder;
import graphql.GraphQLError;

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
    public GraphQLError handleAccessDenied(AccessDeniedException ex) {
        return safeError("Not authorized");
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

    @GraphQlExceptionHandler
    public GraphQLError handleGeneric(Exception ex) {
        log.error("Unhandled server error", ex);
        return safeError("Internal server error");
    }
}
