const { spawn } = require('node:child_process')
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { logger } from './logger';
import { pool, runQuery } from './db';

export function getPool() {
    return pool;
}

export { runQuery, pool };

export async function shellExec(cmd: string, args: any[], timeout?: number): Promise<string> { // timeout = in ms
  return new Promise((resolve, reject) => {
      let options: any = {}
      if (timeout) options.timeout = timeout
      const child = spawn(cmd, args, options)
      let resData = ""
      child.stdout.on('data', (data: string)=> {
          resData += data
      })
    
      child.stderr.on('data', (data: any) => {
          logger.error(`shell command error: ${data}`)
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
    spawnError?: NodeJS.ErrnoException;  // the process never started, e.g. code ENOENT
}

export interface ShellExecDetailedOptions {
    timeoutMs: number;
    maxStdoutBytes: number;
    maxStderrBytes?: number;
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
        let settled = false;

        const child = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'] });

        const timer = setTimeout(() => {
            timedOut = true;
            child.kill('SIGKILL');
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
                child.kill('SIGKILL');
                return;
            }
            stdoutChunks.push(chunk);
            stdoutBytes += chunk.length;
        });

        child.stderr.on('data', (chunk: Buffer) => {
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
                stdoutOverflow
            });
        });
    });
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
  