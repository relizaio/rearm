const { spawn } = require('node:child_process')
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { logger } from './logger';
import { pool, runQuery, withTransaction } from './db';

export function getPool() {
    return pool;
}

export { runQuery, withTransaction, pool };

// stderrLevel: the level each stderr chunk is logged at. 'error' (the default) keeps the historical
// `shell command error:` line; 'info' and 'warn' log `shell command stderr:` (SCORE-68 ADR-4).
export async function shellExec(cmd: string, args: any[], timeout?: number, stderrLevel: 'error' | 'warn' | 'info' = 'error'): Promise<string> { // timeout = in ms
  return new Promise((resolve, reject) => {
      let options: any = {}
      if (timeout) options.timeout = timeout
      const child = spawn(cmd, args, options)
      let resData = ""
      child.stdout.on('data', (data: string)=> {
          resData += data
      })
    
      child.stderr.on('data', (data: any) => {
          if (stderrLevel === 'error') {
              logger.error(`shell command error: ${data}`)
          } else {
              logger[stderrLevel](`shell command stderr: ${data}`)
          }
      })

      child.on('exit', (code: number) => {
          if (code !== 0) logger.error(`shell process exited with code ${code}`)
          if (code === 0) {
              if (resData) {
                  resData = resData.replace(/\n$/, "")
              }
              resolve(resData)
          } else {
              logger.error(resData)
              reject(resData)
          }
      })
  })
}

export interface ShellResult {
    code: number | null;                 // exit code; null when killed or never started
    signal: string | null;
    stdout: string;                      // complete up to maxStdoutBytes, not trimmed
    stderr: string;                      // first maxStderrBytes (default 64 KiB)
    timedOut: boolean;                   // our timer fired
    stdoutOverflow: boolean;             // stdout passed maxStdoutBytes; process was killed
    stderrOverflow: boolean;             // stderr passed maxStderrBytes; the rest was dropped (the process runs on)
    pid?: number;                        // the child's pid; absent when the process never started
    spawnError?: NodeJS.ErrnoException;  // the process never started, e.g. code ENOENT
}

export interface ShellExecDetailedOptions {
    timeoutMs: number;
    maxStdoutBytes: number;
    maxStderrBytes?: number;
    /** The complete environment of the child. When given, nothing of rebom's environment is inherited. */
    env?: NodeJS.ProcessEnv;
    /**
     * Start the child as the leader of its own process group and kill the whole group (the child's
     * children included) on timeout or overflow. Off by default.
     */
    killProcessGroup?: boolean;
    /** Called once with the child's pid right after it was started (to register it for a shut-down). */
    onSpawn?: (pid: number) => void;
}

const DEFAULT_MAX_STDERR_BYTES = 64 * 1024;

/**
 * Sibling of shellExec for callers that need the exit code, stderr and a bounded stdout.
 * Never rejects and logs nothing. Resolves on 'close' (stdout fully drained), or on
 * 'error' when the process never started. On timeout or stdout overflow the process
 * is killed with SIGKILL and the promise still resolves only once it has closed.
 */
export function shellExecDetailed(cmd: string, args: string[], opts: ShellExecDetailedOptions): Promise<ShellResult> {
    const maxStderrBytes = opts.maxStderrBytes ?? DEFAULT_MAX_STDERR_BYTES;
    return new Promise((resolve) => {
        const stdoutChunks: Buffer[] = [];
        const stderrChunks: Buffer[] = [];
        let stdoutBytes = 0;
        let stderrBytes = 0;
        let timedOut = false;
        let stdoutOverflow = false;
        let stderrOverflow = false;
        let settled = false;

        // Only the options a caller asked for are added, so callers without them spawn exactly as before.
        const spawnOptions: Record<string, unknown> = { stdio: ['ignore', 'pipe', 'pipe'] };
        if (opts.env !== undefined) spawnOptions.env = opts.env;
        if (opts.killProcessGroup) spawnOptions.detached = true;
        const child = spawn(cmd, args, spawnOptions);
        if (child.pid !== undefined && opts.onSpawn) opts.onSpawn(child.pid);

        const kill = () => {
            if (opts.killProcessGroup && child.pid !== undefined) {
                killProcessGroup(child.pid);
            } else {
                child.kill('SIGKILL');
            }
        };

        const timer = setTimeout(() => {
            timedOut = true;
            kill();
        }, opts.timeoutMs);

        const finish = (result: ShellResult) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve(result);
        };

        child.stdout.on('data', (chunk: Buffer) => {
            if (stdoutOverflow) return;
            if (stdoutBytes + chunk.length > opts.maxStdoutBytes) {
                stdoutOverflow = true;
                kill();
                return;
            }
            stdoutChunks.push(chunk);
            stdoutBytes += chunk.length;
        });

        child.stderr.on('data', (chunk: Buffer) => {
            if (stderrBytes + chunk.length > maxStderrBytes) stderrOverflow = true;
            if (stderrBytes >= maxStderrBytes) return;
            const part = chunk.subarray(0, maxStderrBytes - stderrBytes);
            stderrChunks.push(part);
            stderrBytes += part.length;
        });

        child.on('error', (err: NodeJS.ErrnoException) => {
            // 'error' without a pid: the process never started, and no 'close' follows.
            // With a pid (e.g. a failed kill) the 'close' event still settles the call.
            if (child.pid !== undefined) return;
            finish({
                code: null,
                signal: null,
                stdout: '',
                stderr: '',
                timedOut,
                stdoutOverflow,
                stderrOverflow,
                spawnError: err
            });
        });

        child.on('close', (code: number | null, signal: NodeJS.Signals | null) => {
            finish({
                code,
                signal,
                stdout: Buffer.concat(stdoutChunks).toString('utf8'),
                stderr: Buffer.concat(stderrChunks).toString('utf8'),
                timedOut,
                stdoutOverflow,
                stderrOverflow,
                pid: child.pid
            });
        });
    });
}

/**
 * SIGKILL to the process group led by pid. Errors are ignored: the group may be gone already.
 * Falls back to the pid alone when the group cannot be signalled.
 */
export function killProcessGroup(pid: number): void {
    try {
        process.kill(-pid, 'SIGKILL');
    } catch {
        try {
            process.kill(pid, 'SIGKILL');
        } catch {
            // already gone
        }
    }
}

export async function createTmpFiles(dataArr: any[]): Promise<string[]> {
    const tmpDir = os.tmpdir();
    const filePaths: string[] = [];

    for (const data of dataArr) {
        const tmpFilePath = path.join(tmpDir, `${Date.now()}-${Math.random().toString(36).substring(2, 15)}`);
        await fs.promises.writeFile(tmpFilePath, JSON.stringify(data));
        filePaths.push(tmpFilePath);
    }

    return filePaths;
}

export async function createTempFile(data: any): Promise<string> {
    const tmpDir = os.tmpdir();
    const tmpFilePath = path.join(tmpDir, `${Date.now()}-${Math.random().toString(36).substring(2, 15)}`);
    await fs.promises.writeFile(tmpFilePath, JSON.stringify(data));
    return tmpFilePath
}
export async function deleteTmpFiles(filePaths: string[]): Promise<void> {
    for (const filePath of filePaths) {
        try {
            await fs.promises.unlink(filePath);
        } catch (error) {
            logger.error({ err: error }, `Failed to delete temporary file ${filePath}`);
        }
    }
}

export async function deleteTempFile(filePath: string): Promise<void> {
    try {
        await fs.promises.unlink(filePath);
    } catch (error) {
        logger.error({ err: error }, `Failed to delete temporary file ${filePath}`);
    }
}

export async function writeFileAsync(filename: string, content: string): Promise<void> {
    try {
      await fs.promises.writeFile(filename, content);
      console.log(`File ${filename} has been written successfully`);
    } catch (error) {
      console.error(`Error writing to file ${filename}:`, error);
    }
  }
  