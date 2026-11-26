/**
 * Concurrency and disk bounds of the TEA operations (TPF-180 design 3.7).
 *
 * TeaSlotPool: a limit of running calls, a first-in first-out queue with a bound and a wait.
 * Three instances (reads, downloads, results) so that long downloads never starve reads.
 * TeaDiskBudget: one number, the bytes reserved by running downloads plus the bytes of held fetches.
 */

export const TeaSlotRefusal = {
    SLOTS: 'SLOTS',
    QUEUE_FULL: 'QUEUE_FULL'
} as const;
export type TeaSlotRefusal = typeof TeaSlotRefusal[keyof typeof TeaSlotRefusal];

export type TeaSlotGrant =
    | { granted: true, release: () => void }
    | { granted: false, refusal: TeaSlotRefusal };

interface Waiter {
    grant: () => void;
    refuse: () => void;
    timer: NodeJS.Timeout;
}

export class TeaSlotPool {
    private runningCount = 0;
    private readonly waiters: Waiter[] = [];

    constructor(
        readonly limit: number,
        readonly maxQueued: number,
        readonly waitMs: number
    ) {}

    get running(): number {
        return this.runningCount;
    }

    get queued(): number {
        return this.waiters.length;
    }

    /** A slot, after waiting at most waitMs in the queue; or the refusal. Never rejects. */
    acquire(): Promise<TeaSlotGrant> {
        if (this.runningCount < this.limit) {
            this.runningCount++;
            return Promise.resolve({ granted: true, release: this.releaser() });
        }
        if (this.waiters.length >= this.maxQueued) {
            return Promise.resolve({ granted: false, refusal: TeaSlotRefusal.QUEUE_FULL });
        }
        return new Promise<TeaSlotGrant>((resolve) => {
            const waiter: Waiter = {
                grant: () => {
                    clearTimeout(waiter.timer);
                    resolve({ granted: true, release: this.releaser() });
                },
                refuse: () => {
                    clearTimeout(waiter.timer);
                    resolve({ granted: false, refusal: TeaSlotRefusal.SLOTS });
                },
                timer: setTimeout(() => {
                    const index = this.waiters.indexOf(waiter);
                    if (index >= 0) this.waiters.splice(index, 1);
                    resolve({ granted: false, refusal: TeaSlotRefusal.SLOTS });
                }, this.waitMs)
            };
            this.waiters.push(waiter);
        });
    }

    /** A release function that frees the slot once, however often it is called. */
    private releaser(): () => void {
        let released = false;
        return () => {
            if (released) return;
            released = true;
            const next = this.waiters.shift();
            if (next) {
                // the slot passes straight to the next caller; the count stays the same
                next.grant();
            } else {
                this.runningCount--;
            }
        };
    }

    /** Refuses every waiting call (used when the configuration is replaced). */
    drain(): void {
        while (this.waiters.length > 0) {
            this.waiters.shift()!.refuse();
        }
    }
}

/** The smallest number of bytes a held or reserved file counts for. */
export const TEA_FILE_FLOOR_BYTES = 4096;

export class TeaDiskBudget {
    private usedBytes = 0;

    constructor(readonly maxBytes: number) {}

    get used(): number {
        return this.usedBytes;
    }

    /** Reserves bytes when they fit; false (nothing reserved) otherwise. */
    reserve(bytes: number): boolean {
        if (bytes < 0 || this.usedBytes + bytes > this.maxBytes) return false;
        this.usedBytes += bytes;
        return true;
    }

    /** Gives back bytes reserved earlier. */
    free(bytes: number): void {
        this.usedBytes = Math.max(0, this.usedBytes - bytes);
    }
}

/** What a set of files counts against the budget: each file at least TEA_FILE_FLOOR_BYTES. */
export function heldBytesOf(sizes: number[]): number {
    return sizes.reduce((sum, size) => sum + Math.max(size, TEA_FILE_FLOOR_BYTES), 0);
}
