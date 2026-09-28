// One finding form at a human gate (task RD2-18, sweep UI-39). At a gate the gate box's finding rides on the
// verdict; the standalone "File a finding" form, which files a round now and leaves the task at the gate, is
// hidden until the person asks for it from the gate box. The two forms live in sibling sections (the gate box
// and the findings section, on the page and in the drawer), so the choice is kept here, per task.
import { reactive } from 'vue'

/** The gate box's line under its finding form. */
export const GATE_FINDING_MODE = 'Filed with your verdict: on Approve it is a correction the producer addresses next round;'
    + ' on Reject it is a finding that blocks.'

/** The standalone form's line when it is opened at a gate. */
export const FILE_NOW_LINE = 'Files a round now; the task stays at the gate.'

/** Whether the task is held at a human gate. */
export function atHumanGate (task: any): boolean {
    return task?.status === 'ON_HOLD' && task?.hold?.kind === 'HUMAN_GATE'
}

const fileNowOpen = reactive(new Set<string>())

/** Whether the person asked, at this task's gate, for the standalone form. */
export function fileNowOpened (task: any): boolean {
    return !!task?.uuid && fileNowOpen.has(task.uuid)
}

export function toggleFileNow (task: any): void {
    if (!task?.uuid) return
    if (fileNowOpen.has(task.uuid)) fileNowOpen.delete(task.uuid)
    else fileNowOpen.add(task.uuid)
}

/**
 * Whether a finding of this priority blocks on the board, as the server reads it: with no blocking priority
 * (strict) every priority blocks, else those at or above it (a lower number).
 */
export function blocksOnBoard (priority: number, board: any): boolean {
    const bp = board?.blockingPriority ?? null
    return bp == null || priority <= bp
}

/**
 * The priorities a finding filed now may take at a gate (RD2-18 run 1, T-1): only those that do not block, as
 * the server refuses a blocking finding while the task waits on a person's verdict. None on a strict board.
 */
export function fileNowPriorities (board: any, levels: number | null | undefined): number[] {
    return Array.from({ length: levels ?? 3 }, (_, i) => i + 1).filter(p => !blocksOnBoard(p, board))
}

/** What the gate box says when no finding can be filed now: every priority blocks on this board. */
export const EVERY_PRIORITY_BLOCKS = 'Every priority blocks on this board, so a finding goes with your verdict.'

/** The standalone form's line at a gate: it files now, and only what does not block. */
export function fileNowLine (board: any, levels: number | null | undefined): string {
    const ps = fileNowPriorities(board, levels)
    const range = ps.length ? (ps.length === 1 ? `P${ps[0]}` : `P${ps[0]}–P${ps[ps.length - 1]}`) : ''
    return `${FILE_NOW_LINE} Only a finding that does not block (${range}) can be filed here; a blocking one goes with your verdict.`
}

/**
 * Whether the standalone form shows: always outside a gate; at a gate once asked for, and only where a finding
 * that does not block can be filed.
 */
export function standaloneFindingShown (task: any, board?: any, levels?: number | null): boolean {
    if (!atHumanGate(task)) return true
    return fileNowOpened(task) && fileNowPriorities(board, levels).length > 0
}
