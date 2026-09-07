// Shared render functions for a release row's scan state: the pending /
// rejected badge and the vulnerability + violation count circles. Used by
// the branch release table and the component Latest tab so both draw the
// exact same markup (global `.circle` from App.vue) instead of each table
// keeping its own copy that drifts in size and font.
import { h } from 'vue'
import { NSpace } from 'naive-ui'
import constants from '@/utils/constants'
import type { ReleaseScanStatus } from '@/utils/releaseScanStatus'

export type OpenVulnDetails = (row: any, severityFilter: string, typeFilter: string | string[]) => void

export function renderPendingBadge (status: ReleaseScanStatus) {
    const bg = status.kind === 'rejected' ? '#d03050' : status.kind === 'enrichment-pending' ? '#fd8c00' : '#ffc107'
    return h('span', {
        title: status.title,
        style: `display: inline-block; padding: 2px 10px; border-radius: 12px; background: ${bg}; color: white; font-size: 0.8em; white-space: nowrap;`
    }, status.label)
}

function circle (title: string, color: string, value: any, onClick: () => void) {
    return h('div', { title, class: 'circle', style: `background: ${color}; cursor: pointer;`, onClick: (e: Event) => { e.stopPropagation(); onClick() } }, value)
}

/** Vulnerabilities cell: badge when the scan is not ready, five severity circles otherwise, 'N/A' without metrics. */
export function renderVulnerabilityCells (row: any, status: ReleaseScanStatus, open: OpenVulnDetails) {
    if (status.kind !== 'ready') return [renderPendingBadge(status)]
    if (!(row.metrics && row.metrics.lastScanned)) return [h('div'), 'N/A']
    const m = row.metrics
    return [h(NSpace, { size: 1 }, () => [
        circle('Critical Severity Vulnerabilities', constants.VulnerabilityColors.CRITICAL, m.critical, () => open(row, 'CRITICAL', ['Vulnerability', 'Weakness'])),
        circle('High Severity Vulnerabilities', constants.VulnerabilityColors.HIGH, m.high, () => open(row, 'HIGH', ['Vulnerability', 'Weakness'])),
        circle('Medium Severity Vulnerabilities', constants.VulnerabilityColors.MEDIUM, m.medium, () => open(row, 'MEDIUM', ['Vulnerability', 'Weakness'])),
        circle('Low Severity Vulnerabilities', constants.VulnerabilityColors.LOW, m.low, () => open(row, 'LOW', ['Vulnerability', 'Weakness'])),
        circle('Vulnerabilities with Unassigned Severity', constants.VulnerabilityColors.UNASSIGNED, m.unassigned, () => open(row, 'UNASSIGNED', ['Vulnerability', 'Weakness']))
    ])]
}

/** Violations cell: badge when the scan is not ready, three policy circles otherwise, 'N/A' without metrics. */
export function renderViolationCells (row: any, status: ReleaseScanStatus, open: OpenVulnDetails) {
    if (status.kind !== 'ready') return [renderPendingBadge(status)]
    if (!(row.metrics && row.metrics.lastScanned)) return [h('div'), 'N/A']
    const m = row.metrics
    return [h(NSpace, { size: 1 }, () => [
        circle('Licensing Policy Violations', constants.ViolationColors.LICENSE, m.policyViolationsLicenseTotal, () => open(row, '', 'Violation')),
        circle('Security Policy Violations', constants.ViolationColors.SECURITY, m.policyViolationsSecurityTotal, () => open(row, '', 'Violation')),
        circle('Operational Policy Violations', constants.ViolationColors.OPERATIONAL, m.policyViolationsOperationalTotal, () => open(row, '', 'Violation'))
    ])]
}
