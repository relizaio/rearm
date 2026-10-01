// Select options for the task sections (components/task/*). Kept in a util with a spec because a
// broken option list renders blank selects and sends bad values on save, and nothing short of a
// test on the {label, value} shape catches it.
import { INDEXED_TYPES } from './agentDocuments'
import { specWord } from './agentWords'

export type Option<T> = { label: string, value: T }

/** P1..Pn, n being the org's review item priority levels (3 by default). */
export function priorityOptionsOf (levels: number | null | undefined): Option<number>[] {
    return Array.from({ length: levels ?? 3 }, (_, i) => ({ label: `P${i + 1}`, value: i + 1 }))
}

/** Active roles, by name: the roles a task may be authorized for. */
export function roleOptionsOf (roles: any[] | null | undefined): Option<string>[] {
    return (roles ?? []).filter((r: any) => r.active).map((r: any) => ({ label: r.name, value: r.name }))
}

/** What a review item may be about: the types some active role produces, which is where it will route. */
export function aboutOptionsOf (roles: any[] | null | undefined): Option<string>[] {
    const specs = new Set<string>()
    for (const r of roles ?? []) {
        if (!r.active) continue
        for (const o of r.producesOutputs ?? []) if (o?.specification) specs.add(o.specification)
    }
    return [...specs].sort().map(s => ({ label: specWord(s), value: s }))
}

/** The indexed types a person may file a review item in. */
export const fileSpecOptions: Option<string>[] = INDEXED_TYPES
    .map(s => ({ label: s === 'BOARD_TEST_REPORT' ? 'test report' : 'review', value: s }))

/**
 * Document types a role can be required to publish, in the role form. Task-scoped only: a component-scoped
 * document belongs to the thing rather than to a hop, so requiring one per hop would refuse a sign-off on the
 * second task to touch it. BOARD_INVESTIGATION_REPORT (task RD4-12) is what the role delivers when another commissions
 * it; it is never owed on a work task.
 */
export const OUTPUT_TYPE_OPTIONS: Option<string>[] = [
    { label: 'review items', value: 'BOARD_REVIEW_ITEMS' },
    { label: 'test report', value: 'BOARD_TEST_REPORT' },
    { label: 'investigation report (when commissioned)', value: 'BOARD_INVESTIGATION_REPORT' },
]
