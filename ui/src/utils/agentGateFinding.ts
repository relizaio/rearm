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

/** Whether the standalone form shows: always outside a gate, at a gate once asked for. */
export function standaloneFindingShown (task: any): boolean {
    return !atHumanGate(task) || fileNowOpened(task)
}
