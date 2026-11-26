import { TeaCredential, TeaCredentialKind } from './teaRequest';

/**
 * The complete environment of a run (TPF-180 design 3.5): an allow-list, built per call. Nothing
 * of rebom's own environment is inherited but PATH and the trust store settings; the credential
 * travels only here, never in an argument, a file or rebom's own process.env.
 */

const INHERITED = ['PATH', 'SSL_CERT_FILE', 'SSL_CERT_DIR'] as const;

export const TEA_ENV_AUTH_ROOT = 'REARM_TEA_AUTH_ROOT';
export const TEA_ENV_TOKEN = 'REARM_TEA_TOKEN';
export const TEA_ENV_KEY_ID = 'REARM_TEA_APIKEYID';
export const TEA_ENV_KEY_SECRET = 'REARM_TEA_APIKEY';

export function buildTeaChildEnv(credential: TeaCredential | null, callDir: string, parentEnv: NodeJS.ProcessEnv = process.env): NodeJS.ProcessEnv {
    const env: NodeJS.ProcessEnv = {};
    for (const name of INHERITED) {
        const value = parentEnv[name];
        if (value !== undefined) env[name] = value;
    }
    env.HOME = callDir;
    env.TMPDIR = callDir;
    if (credential && credential.kind !== TeaCredentialKind.NONE) {
        env[TEA_ENV_AUTH_ROOT] = credential.authRoot!;
        if (credential.kind === TeaCredentialKind.BEARER_TOKEN) {
            env[TEA_ENV_TOKEN] = credential.token!;
        } else {
            env[TEA_ENV_KEY_ID] = credential.keyId!;
            env[TEA_ENV_KEY_SECRET] = credential.keySecret!;
        }
    }
    return env;
}
