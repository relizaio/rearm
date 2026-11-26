import { logger } from '../../logger';

/**
 * The one log function of the TEA operations (TPF-180 design 3.10). It takes a fixed set of named
 * fields and copies only those, so a call site cannot pass an object with a secret in it. Never
 * logged: a field of the credential, the caller token, the child's environment or streams, a
 * document, a file's content, rebomOptions.
 */
export interface TeaLogFields {
    operation: string;
    outcome: string;
    reason?: string | null;
    correlationId?: string | null;
    exitCode?: number | null;
    durationMs?: number;
    queueWaitMs?: number;
    stdoutBytes?: number;
    apiHost?: string | null;
    rowsByStatus?: Record<string, number>;
    heldBytes?: number;
    org?: string;
    bomUuid?: string | null;
    stored?: string | null;
    ref?: string;
}

const KEYS: readonly (keyof TeaLogFields)[] = [
    'operation', 'outcome', 'reason', 'correlationId', 'exitCode', 'durationMs', 'queueWaitMs', 'stdoutBytes',
    'apiHost', 'rowsByStatus', 'heldBytes', 'org', 'bomUuid', 'stored', 'ref'
];

export function logTeaEvent(fields: TeaLogFields): void {
    const line: Record<string, unknown> = {};
    for (const key of KEYS) {
        const value = fields[key];
        if (value !== undefined && value !== null) line[key] = value;
    }
    logger.info(line, 'TEA operation');
}

/** The host of an API root (never its path, never a user name). Null when it does not parse. */
export function hostOf(apiRoot: string | undefined): string | null {
    if (!apiRoot) return null;
    try {
        return new URL(apiRoot).host;
    } catch {
        return null;
    }
}
