import type {
    ChildProcess, ChildProcessByStdio, ChildProcessWithoutNullStreams, SpawnOptions, SpawnOptionsWithStdioTuple,
    SpawnOptionsWithoutStdio, StdioPipe
} from 'node:child_process';
import type { Readable, Writable } from 'node:stream';
import { holdsOwnName } from './childEnv';

/**
 * The only file under src that may name the Node child process module (TPF-256 design 3.2; the
 * scan test fails on any other). Every child of rebom is started here, with an environment the
 * caller built with buildChildEnv of childEnv.ts.
 *
 * spawn is captured at load, as utils.ts did before: the tests patch it on the module object
 * before any import runs.
 */
const { spawn } = require('node:child_process');

export const CHILD_ENV_GUARD_MESSAGE = 'child process refused: its environment is missing or holds a setting of rebom';

export type ChildSpawnOptions = SpawnOptions & { env: NodeJS.ProcessEnv };

/**
 * spawn with the three arguments as given, after the guard: throws CHILD_ENV_GUARD_MESSAGE before
 * anything starts when the environment is missing or holds a name that starts with POSTGRES_,
 * ENCRYPTION_ or REBOM_. The message is constant: no name, no value. The return types are those
 * spawn gives for the same options.
 */
export function spawnChild(cmd: string, args: readonly string[], options: SpawnOptionsWithStdioTuple<StdioPipe, StdioPipe, StdioPipe> & { env: NodeJS.ProcessEnv }): ChildProcessByStdio<Writable, Readable, Readable>;
export function spawnChild(cmd: string, args: readonly string[], options: SpawnOptionsWithoutStdio & { env: NodeJS.ProcessEnv }): ChildProcessWithoutNullStreams;
export function spawnChild(cmd: string, args: readonly string[], options: ChildSpawnOptions): ChildProcess;
export function spawnChild(cmd: string, args: readonly string[], options: ChildSpawnOptions): ChildProcess {
    if (options?.env == null || holdsOwnName(options.env)) {
        throw new Error(CHILD_ENV_GUARD_MESSAGE);
    }
    return spawn(cmd, args, options);
}
