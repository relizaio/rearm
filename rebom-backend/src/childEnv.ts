import { logger } from './logger';

/**
 * The environment of every child process rebom starts (TPF-256 design 3.1 and 3.3): an allow-list
 * per tool, built per call. Nothing of rebom's own environment reaches a child but the names of
 * the tool's row and the extra names the operator listed in REBOM_CHILD_ENV_PASSTHROUGH and rebom
 * accepted. The TEA children have their own, stricter builder (services/tea/teaChildEnv.ts) and
 * never get the extra names.
 *
 * Nothing of the parent environment is cached: every call reads it again. The only state of the
 * module is the list of accepted extra names, set by configureChildEnv once at start. This module
 * imports the logger and types only and calls the logger only inside configureChildEnv, so tests
 * that replace utils or the logger with a plain stand-in keep loading the services that import it.
 */

export enum ChildTool {
    BASE = 'BASE',                    // a child no entry was written for: the default of both helpers
    REARM_BOM = 'REARM_BOM',          // rearm bomutils merge-boms, convert-spdx, score
    REARM_ENRICH = 'REARM_ENRICH',    // rearm bomutils enrich
    CYCLONEDX_CLI = 'CYCLONEDX_CLI',  // cyclonedx-cli diff, validate
    SSH_KEYGEN = 'SSH_KEYGEN',
    GPG = 'GPG'
}

export const CHILD_ENV_PASSTHROUGH_VARIABLE = 'REBOM_CHILD_ENV_PASSTHROUGH';
export const CHILD_ENV_PASSTHROUGH_LINE = 'child environment passthrough';
export const CHILD_HOME_NONE = '/nonexistent';
export const DEFAULT_CHILD_PATH = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin';
// Accepted extra names that the enrichment run, the one child holding a credential, never gets
// (design 3.10): a Go run time or library setting can make it print or dump the BEAR key.
export const ENRICH_WITHHELD_PREFIXES: readonly string[] = ['GO'];
export const ENRICH_WITHHELD_NAMES: readonly string[] = ['DEBUG_HTTP2_GOROUTINES', 'SSLKEYLOGFILE'];

// The proxy names the enrichment child inherits: the eight that services/tea/teaOutcome.ts knows.
export const CHILD_PROXY_VARIABLES: readonly string[] = [
    'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy'
];

// rebom's own settings and secrets. The guard of childProcess.ts refuses a child environment that
// holds one of them, and the hatch refuses to pass them.
export const REBOM_OWN_PREFIXES: readonly string[] = ['POSTGRES_', 'ENCRYPTION_', 'REBOM_'];

// The hatch refusals of design 3.3, decided on the upper-cased name.
export const PASSTHROUGH_REFUSED_PREFIXES: readonly string[] = [...REBOM_OWN_PREFIXES, 'REARM_', 'LD_', 'CORECLR_'];
export const PASSTHROUGH_REFUSED_PARTS: readonly string[] = ['PASS', 'PWD', 'SECRET', 'TOKEN', 'KEY', 'SALT', 'CREDENTIAL', 'AUTH'];
export const PASSTHROUGH_REFUSED_NAMES: readonly string[] = ['NODE_OPTIONS', 'DOTNET_STARTUP_HOOKS'];

const NAME_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/;

const BASE_INHERITED: readonly string[] = ['PATH', 'LANG', 'LC_ALL'];

interface ChildToolRow {
    inherited: readonly string[];               // copied from the parent when defined there
    set: Readonly<Record<string, string>>;      // set by the builder, after everything else
}

const ROWS: Readonly<Record<ChildTool, ChildToolRow>> = {
    [ChildTool.BASE]: { inherited: BASE_INHERITED, set: {} },
    [ChildTool.REARM_BOM]: { inherited: BASE_INHERITED, set: { HOME: CHILD_HOME_NONE } },
    [ChildTool.REARM_ENRICH]: {
        inherited: [...BASE_INHERITED, 'SSL_CERT_FILE', 'SSL_CERT_DIR', ...CHILD_PROXY_VARIABLES, 'REARM_RESILIENTDNS'],
        set: { HOME: CHILD_HOME_NONE }
    },
    [ChildTool.CYCLONEDX_CLI]: {
        inherited: [...BASE_INHERITED, 'DOTNET_SYSTEM_GLOBALIZATION_INVARIANT', 'DOTNET_BUNDLE_EXTRACT_BASE_DIR', 'HOME', 'TMPDIR'],
        set: {}
    },
    [ChildTool.SSH_KEYGEN]: { inherited: BASE_INHERITED, set: {} },
    [ChildTool.GPG]: { inherited: BASE_INHERITED, set: {} }
};

// GNUPGHOME is set by the caller of the GPG row, per call.
const CALLER_SET_NAMES: readonly string[] = ['GNUPGHOME'];

/** Every name the builder or a caller of a row manages; the hatch refuses each of them. */
export const CHILD_MANAGED_NAMES: readonly string[] = Array.from(new Set([
    ...Object.values(ROWS).flatMap((row) => [...row.inherited, ...Object.keys(row.set)]),
    ...CALLER_SET_NAMES
]));

const MANAGED_UPPER: ReadonlySet<string> = new Set(CHILD_MANAGED_NAMES.map((name) => name.toUpperCase()));

export interface ChildEnvPassthrough { accepted: string[]; refused: string[]; malformed: number; withheldFromEnrichment: string[] }

let passthroughNames: readonly string[] = [];

/** True when the enrichment run never gets this accepted extra name (design 3.10). Pure. */
export function isWithheldFromEnrichment(name: string): boolean {
    const upper = name.toUpperCase();
    return ENRICH_WITHHELD_PREFIXES.some((prefix) => upper.startsWith(prefix)) || ENRICH_WITHHELD_NAMES.includes(upper);
}

function isRefused(name: string): boolean {
    const upper = name.toUpperCase();
    return PASSTHROUGH_REFUSED_PREFIXES.some((prefix) => upper.startsWith(prefix))
        || PASSTHROUGH_REFUSED_PARTS.some((part) => upper.includes(part))
        || PASSTHROUGH_REFUSED_NAMES.includes(upper)
        || MANAGED_UPPER.has(upper);
}

/**
 * Names separated by commas; each piece trimmed, empty pieces dropped. A piece that is not a name
 * is counted as malformed and never kept or echoed (it may be a pasted NAME=value). Pure.
 */
export function parseChildEnvPassthrough(raw: string | undefined): ChildEnvPassthrough {
    const accepted: string[] = [];
    const refused: string[] = [];
    let malformed = 0;
    for (const piece of (raw ?? '').split(',').map((p) => p.trim())) {
        if (piece === '') continue;
        if (!NAME_PATTERN.test(piece)) {
            malformed++;
            continue;
        }
        const target = isRefused(piece) ? refused : accepted;
        if (!target.includes(piece)) target.push(piece);
    }
    return { accepted, refused, malformed, withheldFromEnrichment: accepted.filter(isWithheldFromEnrichment) };
}

/**
 * Reads REBOM_CHILD_ENV_PASSTHROUGH once at start, keeps the accepted names for buildChildEnv and
 * writes one log line with names and a count, never a value. Never throws: a bad list never stops
 * the pod.
 */
export function configureChildEnv(env: NodeJS.ProcessEnv = process.env): ChildEnvPassthrough {
    const result = parseChildEnvPassthrough(env[CHILD_ENV_PASSTHROUGH_VARIABLE]);
    passthroughNames = [...result.accepted];
    try {
        const fields = {
            accepted: result.accepted,
            refused: result.refused,
            malformed: result.malformed,
            withheldFromEnrichment: result.withheldFromEnrichment
        };
        if (result.refused.length > 0 || result.malformed > 0) {
            logger.warn(fields, CHILD_ENV_PASSTHROUGH_LINE);
        } else {
            logger.info(fields, CHILD_ENV_PASSTHROUGH_LINE);
        }
    } catch {
        // the line is information only; a failing logger never stops the pod
    }
    return result;
}

/**
 * The complete environment of one child of the given tool: a new object each call, filled in this
 * order so that what the builder sets wins: the accepted extra names defined in parentEnv (for
 * REARM_ENRICH without the withheld ones), the inherited names of the tool's row defined in
 * parentEnv (PATH falls back to DEFAULT_CHILD_PATH), the values the row sets. parentEnv is never
 * written to and no key is set to undefined.
 */
export function buildChildEnv(tool: ChildTool, parentEnv: NodeJS.ProcessEnv = process.env): NodeJS.ProcessEnv {
    const row = ROWS[tool];
    const env: NodeJS.ProcessEnv = {};
    for (const name of passthroughNames) {
        if (tool === ChildTool.REARM_ENRICH && isWithheldFromEnrichment(name)) continue;
        const value = parentEnv[name];
        if (value !== undefined) env[name] = value;
    }
    for (const name of row.inherited) {
        const value = parentEnv[name];
        if (value !== undefined) env[name] = value;
    }
    if (env.PATH === undefined) env.PATH = DEFAULT_CHILD_PATH;
    for (const [name, value] of Object.entries(row.set)) env[name] = value;
    return env;
}

/** The guard of childProcess.ts: true when a name, upper-cased, starts with one of rebom's own prefixes. */
export function holdsOwnName(env: NodeJS.ProcessEnv): boolean {
    return Object.keys(env).some((name) => {
        const upper = name.toUpperCase();
        return REBOM_OWN_PREFIXES.some((prefix) => upper.startsWith(prefix));
    });
}
