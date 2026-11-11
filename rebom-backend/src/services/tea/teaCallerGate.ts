import { createHash, timingSafeEqual } from 'crypto';
import { logger } from '../../logger';
import { TeaOutcome, TeaReason, teaResult, TeaResultBase } from './teaOutcome';

/**
 * The caller gate of the TEA operations (TPF-180 design 3.2): a shared secret the ReARM backend
 * sends in the X-Rebom-Caller-Token header. Without a configured secret every TEA operation
 * refuses. The gate is the first statement of every TEA resolver.
 */

export const TEA_CALLER_HEADER = 'x-rebom-caller-token';

/** The GraphQL context: the one value copied from the request. */
export interface TeaRequestContext {
    teaCallerToken?: string;
}

const REFUSAL_LOG_INTERVAL_MS = 10000;

let configuredToken: string | null = null;
let refusalsSinceLine = 0;
let lastRefusalLineAt: number | null = null;

/** Set by configureTea: the valid token, or null (not configured). */
export function setTeaCallerToken(token: string | null): void {
    configuredToken = token;
    refusalsSinceLine = 0;
    lastRefusalLineAt = null;
}

function digest(value: string): Buffer {
    return createHash('sha256').update(value, 'utf8').digest();
}

/** Constant time: both values hashed, then the digests compared (equal length by construction). */
function sameToken(presented: string, expected: string): boolean {
    return timingSafeEqual(digest(presented), digest(expected));
}

/** One warn line at most every ten seconds, with the number of refusals since the last one. */
function logRefusal(): void {
    refusalsSinceLine++;
    const now = Date.now();
    if (lastRefusalLineAt !== null && now - lastRefusalLineAt < REFUSAL_LOG_INTERVAL_MS) return;
    logger.warn({ refusals: refusalsSinceLine }, 'TEA caller refused');
    refusalsSinceLine = 0;
    lastRefusalLineAt = now;
}

/**
 * Null when the caller may proceed; otherwise the result to answer with (NOT_CONFIGURED when no
 * valid token is configured, CALLER_REFUSED when the header is absent or wrong: one answer for both).
 */
export function requireTeaCaller(context: TeaRequestContext | undefined): TeaResultBase | null {
    const expected = configuredToken;
    if (expected === null) {
        return teaResult(TeaOutcome.NOT_CONFIGURED, TeaReason.CALLER_TOKEN);
    }
    const presented = context?.teaCallerToken;
    if (typeof presented !== 'string' || !sameToken(presented, expected)) {
        logRefusal();
        return teaResult(TeaOutcome.CALLER_REFUSED, TeaReason.CALLER);
    }
    return null;
}

/** The context function of the server: copies exactly the header value, nothing else. */
export function teaContextOf(headers: Record<string, string | string[] | undefined>): TeaRequestContext {
    const value = headers[TEA_CALLER_HEADER];
    return typeof value === 'string' ? { teaCallerToken: value } : {};
}
