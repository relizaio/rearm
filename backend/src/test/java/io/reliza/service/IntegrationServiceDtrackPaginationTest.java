/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;

/**
 * Guards the contract that {@link IntegrationService#executeDtrackPaginatedCallWithTransform}
 * propagates a thrown {@link RelizaException} from {@link IntegrationService#fetchDtrackPage}
 * instead of silently truncating the result list.
 *
 * <p>Before the silent-failure-stop change, fetchDtrackPage swallowed exceptions and returned
 * null, which the pagination loop treated as "no more pages" — a mid-drain failure would
 * silently return the partial list built up to that point, and the caller would persist an
 * empty/partial vulnerability set, overwriting the artifact's previous good data.
 */
class IntegrationServiceDtrackPaginationTest {

    /** Page size used by both the production loop and the fake pages here. Mirrors CommonVariables.DTRACK_DEFAULT_PAGE_SIZE. */
    private static final int PAGE_SIZE = io.reliza.common.CommonVariables.DTRACK_DEFAULT_PAGE_SIZE;

    /**
     * Two full pages then a thrown RelizaException must NOT return the two pages — the
     * exception must propagate. Returning the partial 500 would be the old silent-failure
     * shape.
     */
    @Test
    void midDrainExceptionPropagatesInsteadOfReturningPartialList() {
        AtomicInteger calls = new AtomicInteger(0);
        IntegrationService svc = new IntegrationService(null) {
            @Override
            IntegrationService.DtrackPageResult fetchDtrackPage(String baseUri, String apiToken,
                    String existingParams, String separator, int pageNumber, int pageSize)
                    throws RelizaException {
                int n = calls.incrementAndGet();
                if (n <= 2) {
                    // Two full pages of opaque objects — enough to keep hasMorePages true.
                    List<Object> page = new ArrayList<>(pageSize);
                    for (int i = 0; i < pageSize; i++) page.add("item-page" + n + "-" + i);
                    return new IntegrationService.DtrackPageResult(page, /*totalCount*/ 0);
                }
                // Mid-drain failure (simulated HTTP/parse error). Previously returned null
                // and the loop exited cleanly; now must throw and propagate.
                throw new RelizaException("simulated transient DTrack failure on page " + n);
            }
        };

        RelizaException ex = assertThrows(RelizaException.class,
            () -> svc.executeDtrackPaginatedCallWithTransform(
                "https://dtrack.example/api/v1/vulnerability/project/abc",
                "fake-token",
                "",
                raw -> raw.stream().map(Object::toString).toList()));

        // Exception message echoes the page number so the operator can locate the failure.
        assertEquals(true, ex.getMessage().contains("page 3"),
            "exception message should identify the failing page; got: " + ex.getMessage());
        // Three pages attempted: two succeeded, third threw. No fourth call.
        assertEquals(3, calls.get(), "loop should have stopped at the throwing page");
    }

    /**
     * Null return from fetchDtrackPage (the legitimate empty-body branch, e.g. DTrack
     * returning 200 with an empty body) must still be treated as end-of-data — not as
     * an error. Whatever was accumulated up to that point is returned cleanly.
     */
    @Test
    void nullBodyOnNthPageStillTerminatesLoopCleanly() throws RelizaException {
        AtomicInteger calls = new AtomicInteger(0);
        IntegrationService svc = new IntegrationService(null) {
            @Override
            IntegrationService.DtrackPageResult fetchDtrackPage(String baseUri, String apiToken,
                    String existingParams, String separator, int pageNumber, int pageSize)
                    throws RelizaException {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    List<Object> page = new ArrayList<>(pageSize);
                    for (int i = 0; i < pageSize; i++) page.add("ok-" + i);
                    return new IntegrationService.DtrackPageResult(page, /*totalCount*/ 0);
                }
                // Null body signals end-of-data legitimately (NOT an error).
                return null;
            }
        };

        List<String> out = svc.executeDtrackPaginatedCallWithTransform(
            "https://dtrack.example/api/v1/vulnerability/project/abc",
            "fake-token",
            "",
            raw -> raw.stream().map(Object::toString).toList());

        assertEquals(PAGE_SIZE, out.size(),
            "partial page from a null follow-up must be retained as final result");
        assertEquals(2, calls.get(), "loop should issue a follow-up call then stop on null");
    }
}
