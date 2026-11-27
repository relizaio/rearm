/**
 * The environment of one `rearm bomutils enrich` run, built per call. The BEAR API key travels
 * only here, never in an argument, a temp file, a log line or rebom's own process.env: a command
 * line is readable by every process that can see the child, its environment only by the same user
 * and root.
 *
 * The rearm CLI binds every flag to a REARM_<FLAG> variable (viper AutomaticEnv), so
 * REARM_BEARAPIKEY supplies --bearApiKey when the flag is not given. Nothing else of rebom's
 * environment is inherited: the rest is the REARM_ENRICH row of childEnv.ts (TPF-256 design 3.1).
 */

import { buildChildEnv, ChildTool } from '../../childEnv';

export const BEAR_ENV_API_KEY = 'REARM_BEARAPIKEY';

// Constant on purpose: node's own spawn error for a NUL byte quotes the value.
export const BEAR_API_KEY_NUL_MESSAGE = 'BEAR API key holds a NUL byte';

/**
 * A new object: the REARM_ENRICH environment of childEnv.ts built from parentEnv, without
 * REARM_BEARAPIKEY, then REARM_BEARAPIKEY set to the key when the key is not empty. An empty key
 * leaves the name out, so the CLI sends no key header and a REARM_BEARAPIKEY of rebom's own
 * environment never reaches it. parentEnv is never written to.
 */
export function buildEnrichmentChildEnv(bearApiKey: string, parentEnv: NodeJS.ProcessEnv = process.env): NodeJS.ProcessEnv {
    if (typeof bearApiKey === 'string' && bearApiKey.includes('\0')) {
        throw new Error(BEAR_API_KEY_NUL_MESSAGE);
    }
    const env: NodeJS.ProcessEnv = buildChildEnv(ChildTool.REARM_ENRICH, parentEnv);
    delete env[BEAR_ENV_API_KEY];
    if (typeof bearApiKey === 'string' && bearApiKey !== '') {
        env[BEAR_ENV_API_KEY] = bearApiKey;
    }
    return env;
}
