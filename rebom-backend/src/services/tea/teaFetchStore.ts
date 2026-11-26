import { randomBytes } from 'crypto';
import * as fs from 'fs';
import { logger } from '../../logger';
import { TeaDocumentClass } from './teaDocument';
import { TeaDiskBudget } from './teaSlots';

/**
 * The held fetches (TPF-180 design 3.6.1): fetchId to directory, rows, sizes, digests and expiry,
 * in the memory of this process. The expiry is absolute and not extended by use; removal waits for
 * the operations running on the fetch. Nothing survives a restart (the sweep removes the files).
 */

export interface TeaHeldFile {
    number: number;
    path: string;
    sizeBytes: number;
    sha256: string;
    documentClass: TeaDocumentClass;
}

export interface TeaHeldFetch {
    fetchId: string;
    callDir: string;
    files: Map<number, TeaHeldFile>;
    expiresAt: number;
    heldBytes: number;
}

interface Entry {
    fetch: TeaHeldFetch;
    users: number;
    released: boolean;
}

const EXPIRY_INTERVAL_MS = 10000;

/** What use() answers when the fetch is unknown, released or expired. */
export const TEA_FETCH_NOT_HELD = Symbol('TEA_FETCH_NOT_HELD');

export class TeaFetchStore {
    private readonly entries = new Map<string, Entry>();
    private timer: NodeJS.Timeout | null = null;

    constructor(
        private readonly budget: TeaDiskBudget,
        private readonly now: () => number = Date.now
    ) {}

    get size(): number {
        return this.entries.size;
    }

    start(): void {
        if (this.timer) return;
        this.timer = setInterval(() => {
            this.expire().catch((err) => logger.error({ err }, 'TEA: expiry of held fetches failed'));
        }, EXPIRY_INTERVAL_MS);
        this.timer.unref();
    }

    stop(): void {
        if (this.timer) clearInterval(this.timer);
        this.timer = null;
    }

    /** Registers held files under a new fetchId (16 random bytes as hex). */
    register(callDir: string, files: TeaHeldFile[], holdSeconds: number, heldBytes: number): TeaHeldFetch {
        const fetchId = randomBytes(16).toString('hex');
        const fetch: TeaHeldFetch = {
            fetchId,
            callDir,
            files: new Map(files.map((file) => [file.number, file])),
            expiresAt: this.now() + holdSeconds * 1000,
            heldBytes
        };
        this.entries.set(fetchId, { fetch, users: 0, released: false });
        return fetch;
    }

    private isExpired(entry: Entry): boolean {
        return this.now() >= entry.fetch.expiresAt;
    }

    /**
     * Runs fn on a held fetch; TEA_FETCH_NOT_HELD when it is unknown, released or expired.
     * The fetch is not removed while fn runs.
     */
    async use<T>(fetchId: string, fn: (fetch: TeaHeldFetch) => Promise<T>): Promise<T | typeof TEA_FETCH_NOT_HELD> {
        const entry = this.entries.get(fetchId);
        if (!entry || entry.released) return TEA_FETCH_NOT_HELD;
        if (this.isExpired(entry)) {
            await this.release(fetchId);
            return TEA_FETCH_NOT_HELD;
        }
        entry.users++;
        try {
            return await fn(entry.fetch);
        } finally {
            entry.users--;
            if (entry.released && entry.users === 0) await this.remove(entry);
        }
    }

    /** Removes a fetch at once (or when its last running operation ends). Unknown ids are fine. */
    async release(fetchId: string): Promise<void> {
        const entry = this.entries.get(fetchId);
        if (!entry || entry.released) return;
        entry.released = true;
        if (entry.users === 0) await this.remove(entry);
    }

    /** Removes every expired fetch. */
    async expire(): Promise<void> {
        for (const [fetchId, entry] of [...this.entries]) {
            if (this.isExpired(entry)) await this.release(fetchId);
        }
    }

    private async remove(entry: Entry): Promise<void> {
        if (!this.entries.has(entry.fetch.fetchId)) return;
        this.entries.delete(entry.fetch.fetchId);
        this.budget.free(entry.fetch.heldBytes);
        try {
            await fs.promises.rm(entry.fetch.callDir, { recursive: true, force: true });
        } catch (err) {
            logger.error({ err }, 'TEA: failed to remove a held fetch');
        }
    }

    /** Forgets every fetch and removes its files at once (shut-down and reconfiguration). */
    clearSync(): void {
        this.stop();
        for (const entry of this.entries.values()) {
            this.budget.free(entry.fetch.heldBytes);
            try {
                fs.rmSync(entry.fetch.callDir, { recursive: true, force: true });
            } catch {
                // the sweep at the next start removes what is left
            }
        }
        this.entries.clear();
    }
}
