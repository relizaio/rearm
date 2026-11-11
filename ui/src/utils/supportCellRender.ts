// The release SBOM Components list's Support column, rendered (FDAW-68).
//
// Out of ReleaseView.vue so a spec can mount exactly the column's output for each branch
// (supportCellRender.spec.ts). Every decision is supportCellOf's (supportCell.ts); this module
// adds no rule of its own: it branches on the cell's kind and reads the rest from its fields.

import { h } from 'vue'
import type { VNode } from 'vue'
import { NTag } from 'naive-ui'
import { supportCellOf } from './supportCell'

/**
 * The Support cell of one component.
 *
 * `risk` is the device-risk badge the caller built (ReleaseView's deviceRiskBadge), or null.
 * The device check is a second, independent verdict, so it rides along on every branch: a
 * component can carry dates, hence a device verdict, without a support record once a
 * non-MANUAL writer exists.
 *
 * The dash means "nobody has recorded anything", and it is decided by whether a support record
 * exists (attestationState), not by the END_OF_SUPPORT milestone's source: a level, a
 * justification or a justified unknown without a date is a record, and showing a dash for it
 * erased the disclosure from the screen (FDAW-65). "Nobody has recorded anything" and
 * "somebody recorded that it is unknown" are different disclosures.
 *
 * A withdrawn record shows its tag only. Its facts stay on the row -- withdrawal supersedes,
 * it does not erase -- but printing "EOS 2025-12-31" or a level beside "Withdrawn" would put
 * the retracted claim back on screen as though it still stood.
 */
export function renderSupportCell (c: any, risk: VNode | null): VNode {
    const cell = supportCellOf(c)
    const riskLine = risk ? h('div', { style: 'margin-top: 3px;' }, [risk]) : null
    if (cell.kind === 'none') {
        // Escaped, not a literal em-dash: the source stays plain ASCII per coding_principles,
        // and the rendered output is identical.
        return h('div', [h('span', { style: 'color: #999;', 'data-testid': 'support-dash' }, '\u2014'),
            riskLine])
    }
    const tag = cell.tag
    const els: any[] = [h(NTag, { size: 'small', type: tag.type, round: true }, () => tag.label)]
    if (cell.kind === 'live') {
        if (cell.level) {
            els.push(h('span', { style: 'margin-left: 6px; font-size: 11px;', 'data-testid': 'support-level' },
                cell.level))
        }
        if (cell.eosDate) {
            els.push(h('span', { style: 'margin-left: 6px; font-size: 11px; color: #999;', 'data-testid': 'support-eos' },
                `EOS ${cell.eosDate}`))
        } else if (cell.unknownLabel) {
            // The justified unknown of FDAW-14 3.9: the label, and a bulk tag when a sweep wrote it.
            els.push(h('span', { style: 'margin-left: 6px; font-size: 11px; color: #999;',
                'data-testid': 'eos-unknown' }, `not available (${cell.unknownLabel})`))
            if (cell.unknownBulk) {
                els.push(h(NTag, { size: 'tiny', style: 'margin-left: 4px;', 'data-testid': 'eos-unknown-bulk' },
                    () => 'bulk'))
            }
        }
    }
    if (riskLine) els.push(riskLine)
    return h('div', els)
}
