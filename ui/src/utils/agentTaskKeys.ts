// The task key wherever a person reads a task's name (task RD2-22, board-documents.md D12: the key leads
// every human surface). The kanban card, the review banner and the board picker name tasks and boards
// the way the task page, the PERT and the timeline already do.
import { h, VNode } from 'vue'
import { NTag } from 'naive-ui'

/** "RD2-22 Task key missing (reviewer)": a task awaiting human review in the board's banner. */
export function reviewBannerLabel (t: any): string {
    const title = (t?.title ?? '').trim()
    const name = t?.key ? [t.key, title].filter(Boolean).join(' ')
        : t?.externalRef ? '#' + String(t.externalRef).split('#').pop() : title
    const role = t?.hold?.gateRole
    return role ? `${name} (${role})` : name
}

export interface BoardOption {
    label: string
    value: string
    /** The board carries a pause: the picker says so beside its name. */
    paused: boolean
}

/** The board picker's options: "<prefix> · <name>", each paused board flagged. */
export function boardPickerOptions (boards: any[] | null | undefined): BoardOption[] {
    return (boards ?? []).filter(b => b?.uuid).map(b => ({
        label: b.taskPrefix ? `${b.taskPrefix} · ${b.name}` : b.name,
        value: b.uuid,
        paused: !!b.pause,
    }))
}

/** An option as the picker draws it: its label, and a "paused" tag after it on a paused board. */
export function renderBoardOption (option: BoardOption): VNode {
    return h('span', { class: 'boardopt' }, [
        option.label,
        option.paused ? h(NTag, { size: 'tiny', bordered: false, type: 'warning', style: 'margin-left: 6px',
            'data-testid': 'board-option-paused' }, { default: () => 'paused' }) : null,
    ])
}
