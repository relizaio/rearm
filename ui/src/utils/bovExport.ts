/**
 * CycloneDX 1.6 BOV (Bill of Vulnerabilities) export utility.
 * Builds BOV JSON purely from in-memory findings data — no backend calls required.
 */

import { isSuppressedAnalysisState } from '@/constants/vulnAnalysis'

export interface BovExportFilters {
    types: string[]
    severities: string[]
    includeSuppressed: boolean
    includeAnalysis: boolean
}

const ANALYSIS_STATE_MAP: Record<string, string> = {
    IN_TRIAGE: 'in_triage',
    EXPLOITABLE: 'exploitable',
    NOT_AFFECTED: 'not_affected',
    FALSE_POSITIVE: 'false_positive',
    // Internal FIXED maps to CDX `resolved`
    FIXED: 'resolved',
    RESOLVED: 'resolved',
    RESOLVED_WITH_PEDIGREE: 'resolved_with_pedigree'
}

const SEVERITY_MAP: Record<string, string> = {
    CRITICAL: 'critical',
    HIGH: 'high',
    MEDIUM: 'medium',
    LOW: 'low',
    UNASSIGNED: 'unknown'
}

function isSuppressed(row: any): boolean {
    return isSuppressedAnalysisState(row.analysisState)
}

/**
 * Builds a CycloneDX 1.6 BOV JSON string from findings data.
 * Returns null if no rows survive the applied filters.
 */
export function buildBovJson(data: any[], filters: BovExportFilters): string | null {
    const { severities, includeSuppressed, includeAnalysis } = filters

    const rows = data.filter(row => {
        if (row.type !== 'Vulnerability') return false
        if (!severities.includes(row.severity)) return false
        if (!includeSuppressed && isSuppressed(row)) return false
        return true
    })

    if (rows.length === 0) return null

    const vulnerabilities = rows.map(row => {
        const ratings: any[] = []

        if (row.severities && Array.isArray(row.severities) && row.severities.length > 0) {
            for (const s of row.severities) {
                const entry: any = {
                    severity: SEVERITY_MAP[s.severity?.toUpperCase()] ?? 'unknown'
                }
                if (s.source) {
                    entry.source = { name: s.source }
                }
                ratings.push(entry)
            }
        } else {
            ratings.push({
                severity: SEVERITY_MAP[row.severity?.toUpperCase()] ?? 'unknown'
            })
        }

        const vuln: any = {
            'bom-ref': row.id,
            id: row.id,
            ratings
        }

        if (includeAnalysis) {
            vuln.analysis = {
                state: ANALYSIS_STATE_MAP[row.analysisState] ?? 'in_triage'
            }
        }

        if (row.purl) {
            vuln.affects = [{ ref: row.purl }]
        }

        if (row.aliases && Array.isArray(row.aliases) && row.aliases.length > 0) {
            vuln.references = row.aliases.map((a: any) => ({
                id: a.aliasId,
                source: { name: a.type }
            }))
        }

        return vuln
    })

    const bov = {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        version: 1,
        serialNumber: `urn:uuid:${crypto.randomUUID()}`,
        metadata: {
            timestamp: new Date().toISOString(),
            tools: {
                components: [
                    {
                        type: 'application',
                        supplier: {
                            name: 'Reliza Incorporated',
                            url: [
                                'https://reliza.io',
                                'https://rearmhq.com'
                            ],
                            contact: [
                                {
                                    name: 'Reliza Incorporated',
                                    email: 'info@reliza.io'
                                }
                            ]
                        },
                        authors: [
                            {
                                name: 'Reliza Incorporated',
                                email: 'info@reliza.io'
                            }
                        ],
                        group: 'io.reliza',
                        name: 'ReARM',
                        description: 'Supply Chain Evidence Store',
                        externalReferences: [
                            {
                                type: 'website',
                                url: 'https://rearmhq.com'
                            },
                            {
                                type: 'vcs',
                                url: 'ssh://git@github.com/relizaio/rearm.git'
                            },
                            {
                                type: 'documentation',
                                url: 'https://docs.rearmhq.com'
                            }
                        ]
                    }
                ]
            }
        },
        vulnerabilities
    }

    return JSON.stringify(bov, null, 2)
}

/**
 * Triggers a browser download of the given JSON string as a .json file.
 */
export function downloadBovJson(json: string, filenamePrefix: string): void {
    const dateStr = new Date().toISOString().slice(0, 10)
    const filename = `${filenamePrefix}-${dateStr}.json`
    const blob = new Blob([json], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
}
