// The release History's Action and Scope columns (FDAW-78 architecture 3.4, L3).
//
// Out of ReleaseView.vue so a spec can mount the same column definitions the page uses
// (releaseHistoryColumns.spec.ts), the FDAW-68 supportCellRender.ts pattern. The stored event
// values do not change: these columns translate them. The details column stays in ReleaseView.vue.

import { HISTORY_SCOPE_LABELS, historyActionLabel, labelOrHumanized } from './fdaLabels'

/** Action: the act word of a SUBMISSION row (Created, Resubmitted, Cancelled, ...), else the action's label. */
export const historyActionColumn = {
    key: 'rua',
    title: 'Action',
    render: (row: any): string => historyActionLabel(row),
}

/** Scope: the scope's label, for every scope, FDA or not; an unknown scope reads humanized. */
export const historyScopeColumn = {
    key: 'rus',
    title: 'Scope',
    render: (row: any): string => labelOrHumanized(HISTORY_SCOPE_LABELS, row?.rus),
}
