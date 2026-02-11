/**
 * Shared utilities for finding/vulnerability display components.
 * Extracted from FindingChangesDisplay.vue and FindingChangesDisplayWithAttribution.vue.
 */

import Swal from 'sweetalert2'

export const SEVERITY_ORDER = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED', '-']

export function getSeverityIndex(severity?: string): number {
    if (!severity) return SEVERITY_ORDER.length
    const index = SEVERITY_ORDER.indexOf(severity)
    return index === -1 ? SEVERITY_ORDER.length : index
}

export function getSeverityTagType(severity?: string): 'default' | 'error' | 'warning' | 'info' | 'success' | 'primary' {
    switch (severity) {
        case 'CRITICAL':
        case 'HIGH':
            return 'error'
        case 'MEDIUM':
            return 'warning'
        case 'LOW':
            return 'info'
        case 'UNASSIGNED':
            return 'default'
        default:
            return 'default'
    }
}

export function getFindingTypeTagType(type: string): 'default' | 'error' | 'warning' | 'info' | 'success' | 'primary' {
    switch (type) {
        case 'VULN':
            return 'error'
        case 'VIOLATION':
            return 'warning'
        case 'WEAKNESS':
            return 'info'
        default:
            return 'default'
    }
}

export function getFindingUrl(id: string): string | null {
    if (!id) return null
    if (id.startsWith('ALPINE-CVE-') || id.startsWith('CVE-') || id.startsWith('GHSA-')) {
        return `https://osv.dev/vulnerability/${id}`
    }
    if (id.startsWith('CWE-')) {
        const raw = id.slice(4)
        const num = String(parseInt(raw, 10))
        if (num && num !== 'NaN') {
            return `https://cwe.mitre.org/data/definitions/${num}.html`
        }
    }
    return null
}

const LS_KEY = 'rearm_external_link_consent_until'

export async function openExternalLink(href: string): Promise<void> {
    try {
        const now = Date.now()
        const stored = localStorage.getItem(LS_KEY)
        if (stored && Number(stored) > now) {
            window.open(href, '_blank')
            return
        }
        const result = await Swal.fire({
            icon: 'info',
            title: 'Open external link?\n',
            text: 'This will open a vulnerability database resource external to ReARM. Please confirm that you want to proceed.',
            showCancelButton: true,
            confirmButtonText: 'Open',
            cancelButtonText: 'Cancel',
            input: 'checkbox',
            inputValue: 0,
            inputPlaceholder: "Don't ask me again for 15 days"
        })
        if (result.isConfirmed) {
            if (result.value === 1) {
                const fifteenDaysMs = 15 * 24 * 60 * 60 * 1000
                localStorage.setItem(LS_KEY, String(now + fifteenDaysMs))
            }
            window.open(href, '_blank')
        }
    } catch (err) {
        window.open(href, '_blank')
    }
}
