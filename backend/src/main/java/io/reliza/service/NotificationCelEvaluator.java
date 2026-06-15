/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.dto.notifications.EvaluationMode;

/**
 * Shared seam for notification subscription CEL filter evaluation.
 *
 * <p>The only implementation is the Pro-only, dev.cel-backed
 * {@code io.reliza.service.saas.NotificationCelEvaluatorImpl}. This
 * interface lives in shared code so {@code NotificationFanOutService}
 * (shared, mirrored to CE) can depend on it without importing the
 * {@code saas/} evaluator. On CE the impl bean is absent: the fan-out
 * autowires this interface as {@code @Autowired(required=false)} and
 * delivers unfiltered (match-all) when it is null.
 */
public interface NotificationCelEvaluator {

    /**
     * Validate an expression at subscription-save time. Throws
     * {@link RelizaException} with a descriptive message if the
     * expression is syntactically invalid, too long, too deep, or
     * exceeds the AST node-count budget.
     */
    void validate(String celExpression, EvaluationMode mode) throws RelizaException;

    /**
     * Evaluate a boolean CEL expression against a notification event.
     */
    boolean evaluate(String celExpression, EvaluationMode mode, NotificationOutboxEvent event)
            throws RelizaException;
}
