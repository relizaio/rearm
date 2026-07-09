import pdfMake from 'pdfmake/build/pdfmake'
import pdfFonts from 'pdfmake/build/vfs_fonts'

pdfMake.vfs = pdfFonts.vfs

// ==================== Types ====================

export type ChangelogTab = 'code' | 'sbom' | 'findings' | 'vulnerabilities'

export interface ChangelogPdfOptions {
    title: string
    orgName: string
    dateRange?: string
    aggregationType: 'NONE' | 'AGGREGATED'
    activeTab: ChangelogTab
    changelog: any
    filenamePrefix?: string
}

interface PdfCell {
    text: string
    color?: string
    bold?: boolean
    colSpan?: number
    fillColor?: string
}

interface PdfRow {
    cells: PdfCell[]
}

// ==================== Severity Colors ====================

function getSeverityColor(severity: string): string {
    switch ((severity || '').toUpperCase()) {
        case 'CRITICAL': return '#8B0000'
        case 'HIGH': return '#FF8C00'
        case 'MEDIUM': return '#DAA520'
        case 'LOW': return '#228B22'
        default: return '#888888'
    }
}

function getStatusColor(status: string): string {
    switch (status) {
        case 'New': return '#d03050'
        case 'Resolved': return '#18a058'
        case 'Still Present': return '#f0a020'
        default: return '#333333'
    }
}

// ==================== Code Changes ====================

interface FlatNoneEntry {
    branchLabel: string
    release: any
}

// Flatten NONE-mode branches into a chronological (newest-first) list of {branchLabel, release}.
// Matches the on-screen flattened render so PDF export stays in sync.
function flattenNoneReleases(changelog: any): FlatNoneEntry[] {
    const entries: FlatNoneEntry[] = []
    for (const branch of (changelog.branches || [])) {
        const branchLabel = branch.componentName ? `${branch.componentName} / ${branch.branchName || ''}` : (branch.branchName || '')
        for (const release of (branch.releases || [])) {
            entries.push({ branchLabel, release })
        }
    }
    // Org-level NONE has an extra components layer.
    for (const comp of (changelog.components || [])) {
        for (const branch of (comp.branches || [])) {
            const branchLabel = `${comp.componentName || ''} / ${branch.branchName || ''}`
            for (const release of (branch.releases || [])) {
                entries.push({ branchLabel, release })
            }
        }
    }
    entries.sort((a, b) => {
        const aDate = a.release.createdDate ? new Date(a.release.createdDate).getTime() : 0
        const bDate = b.release.createdDate ? new Date(b.release.createdDate).getTime() : 0
        return bDate - aDate
    })
    return entries
}

// Newest-first comparator by createdDate.
function byCreatedDateDesc(a: { createdDate?: string }, b: { createdDate?: string }): number {
    const aDate = a.createdDate ? new Date(a.createdDate).getTime() : 0
    const bDate = b.createdDate ? new Date(b.createdDate).getTime() : 0
    return bDate - aDate
}

// A full-width section-header row (used to label each product release group).
function sectionHeaderRow(label: string, columnCount: number): PdfRow {
    const cells: PdfCell[] = [{ text: label, bold: true, colSpan: columnCount, fillColor: '#eef3ff' }]
    for (let i = 1; i < columnCount; i++) {
        cells.push({ text: '', fillColor: '#eef3ff' })
    }
    return { cells }
}

// Builds grouped rows for a NoneProductChangelog: a section-header row per product
// release (newest first) followed by that release's child-component rows. Product
// releases that produced no rows for the current tab are skipped.
function buildProductGroupedRows(
    changelog: any,
    columnCount: number,
    emitRelease: (branchLabel: string, release: any) => PdfRow[]
): PdfRow[] {
    const rows: PdfRow[] = []
    const groups = [...(changelog.productReleases || [])].sort(byCreatedDateDesc)
    for (const group of groups) {
        const childEntries: { branchLabel: string; release: any }[] = []
        for (const branch of (group.branches || [])) {
            const branchLabel = branch.componentName
                ? `${branch.componentName} / ${branch.branchName || ''}`
                : (branch.branchName || '')
            for (const release of (branch.releases || [])) {
                childEntries.push({ branchLabel, release })
            }
        }
        childEntries.sort((a, b) => byCreatedDateDesc(a.release, b.release))
        const groupRows: PdfRow[] = []
        for (const e of childEntries) {
            groupRows.push(...emitRelease(e.branchLabel, e.release))
        }
        if (groupRows.length > 0) {
            rows.push(sectionHeaderRow(`Product Release: ${group.version || ''}`, columnCount))
            rows.push(...groupRows)
        }
    }
    return rows
}

function buildCodeTable(changelog: any, aggregationType: string): { headers: string[]; rows: PdfRow[]; widths: string[] } {
    const headers = ['Branch', 'Release', 'Change Type', 'Commit Message', 'Author']
    const widths = ['auto', 'auto', 'auto', '*', 'auto']
    const rows: PdfRow[] = []

    const branches = changelog.branches || []
    if (!branches.length && !(changelog.components || []).length && !(changelog.productReleases || []).length) {
        return { headers, rows, widths }
    }

    if (aggregationType === 'NONE' && changelog.__typename === 'NoneChangelog') {
        for (const { branchLabel, release } of flattenNoneReleases(changelog)) {
            for (const commit of (release.commits || [])) {
                rows.push({ cells: [
                    { text: branchLabel },
                    { text: release.version || '' },
                    { text: commit.changeType || '' },
                    { text: commit.message || '' },
                    { text: commit.author || '' }
                ]})
            }
        }
    } else if (aggregationType === 'NONE' && changelog.__typename === 'NoneProductChangelog') {
        rows.push(...buildProductGroupedRows(changelog, headers.length, (branchLabel, release) =>
            (release.commits || []).map((commit: any) => ({ cells: [
                { text: branchLabel },
                { text: release.version || '' },
                { text: commit.changeType || '' },
                { text: commit.message || '' },
                { text: commit.author || '' }
            ]}))
        ))
    } else if (aggregationType === 'AGGREGATED' && changelog.__typename === 'AggregatedChangelog') {
        for (const branch of branches) {
            const branchLabel = branch.componentName ? `${branch.componentName} / ${branch.branchName || ''}` : (branch.branchName || '')
            for (const group of (branch.commitsByType || [])) {
                for (const commit of (group.commits || [])) {
                    rows.push({ cells: [
                        { text: branchLabel },
                        { text: '' },
                        { text: group.changeType || '' },
                        { text: commit.message || '' },
                        { text: commit.author || '' }
                    ]})
                }
            }
        }
    }

    return { headers, rows, widths }
}

// ==================== SBOM Changes ====================

function buildSbomTable(changelog: any, aggregationType: string): { headers: string[]; rows: PdfRow[]; widths: string[] } {
    if (aggregationType === 'AGGREGATED') {
        return buildSbomAggregatedTable(changelog)
    }
    return buildSbomNoneTable(changelog)
}

function buildSbomNoneTable(changelog: any): { headers: string[]; rows: PdfRow[]; widths: string[] } {
    const headers = ['Branch', 'Release', 'Status', 'PURL', 'Name', 'Version']
    const widths = ['auto', 'auto', 'auto', '*', 'auto', 'auto']

    const emitSbom = (branchLabel: string, release: any): PdfRow[] => {
        const out: PdfRow[] = []
        const sbom = release.sbomChanges
        if (!sbom) return out
        for (const art of (sbom.addedArtifacts || [])) {
            out.push({ cells: [
                { text: branchLabel },
                { text: release.version || '' },
                { text: 'Added', color: '#18a058' },
                { text: art.purl || '' },
                { text: art.name || '' },
                { text: art.version || '' }
            ]})
        }
        for (const art of (sbom.removedArtifacts || [])) {
            out.push({ cells: [
                { text: branchLabel },
                { text: release.version || '' },
                { text: 'Removed', color: '#d03050' },
                { text: art.purl || '' },
                { text: art.name || '' },
                { text: art.version || '' }
            ]})
        }
        return out
    }

    if (changelog.__typename === 'NoneProductChangelog') {
        return { headers, rows: buildProductGroupedRows(changelog, headers.length, emitSbom), widths }
    }

    const rows: PdfRow[] = []
    for (const { branchLabel, release } of flattenNoneReleases(changelog)) {
        rows.push(...emitSbom(branchLabel, release))
    }
    return { headers, rows, widths }
}

function buildSbomAggregatedTable(changelog: any): { headers: string[]; rows: PdfRow[]; widths: string[] } {
    const headers = ['Status', 'PURL', 'Name', 'Version', 'Attribution']
    const widths = ['auto', '*', 'auto', 'auto', 'auto']
    const rows: PdfRow[] = []

    const sbomChanges = changelog.sbomChanges
    if (!sbomChanges || !sbomChanges.artifacts) return { headers, rows, widths }

    const sorted = [...sbomChanges.artifacts].sort((a: any, b: any) => {
        if (a.isNetAdded && !b.isNetAdded) return -1
        if (!a.isNetAdded && b.isNetAdded) return 1
        if (a.isNetRemoved && !b.isNetRemoved) return -1
        if (!a.isNetRemoved && b.isNetRemoved) return 1
        return (a.purl || '').localeCompare(b.purl || '')
    })

    for (const art of sorted) {
        const status = art.isNetAdded ? 'Added' : art.isNetRemoved ? 'Removed' : 'Changed'
        const statusColor = art.isNetAdded ? '#18a058' : art.isNetRemoved ? '#d03050' : '#f0a020'
        const attribution = formatAttribution(art.addedIn, art.removedIn, art.addedInCount, art.removedInCount)

        rows.push({ cells: [
            { text: status, color: statusColor },
            { text: art.purl || '' },
            { text: art.name || '' },
            { text: art.version || '' },
            { text: attribution }
        ]})
    }

    return { headers, rows, widths }
}

// ==================== Finding Changes ====================

function buildFindingTable(changelog: any, aggregationType: string): { headers: string[]; rows: PdfRow[]; widths: string[] } {
    if (aggregationType === 'AGGREGATED') {
        return buildFindingAggregatedTable(changelog)
    }
    return buildFindingNoneTable(changelog)
}

function buildFindingNoneTable(changelog: any): { headers: string[]; rows: PdfRow[]; widths: string[] } {
    const headers = ['Branch', 'Release', 'Status', 'Type', 'Issue ID', 'PURL / Location', 'Severity']
    const widths = ['auto', 'auto', 'auto', 'auto', 'auto', '*', 'auto']

    const emitFinding = (branchLabel: string, release: any): PdfRow[] => {
        const fc = release.findingChanges
        if (!fc) return []
        const out: PdfRow[] = []
        addNoneFindingRows(out, branchLabel, release.version, fc)
        return out
    }

    if (changelog.__typename === 'NoneProductChangelog') {
        return { headers, rows: buildProductGroupedRows(changelog, headers.length, emitFinding), widths }
    }

    const rows: PdfRow[] = []
    for (const { branchLabel, release } of flattenNoneReleases(changelog)) {
        rows.push(...emitFinding(branchLabel, release))
    }
    return { headers, rows, widths }
}

function addNoneFindingRows(rows: PdfRow[], branchName: string, version: string, fc: any) {
    for (const v of (fc.appearedVulnerabilities || [])) {
        rows.push({ cells: [
            { text: branchName },
            { text: version },
            { text: 'New', color: getStatusColor('New') },
            { text: 'Vulnerability' },
            { text: v.vulnId || '' },
            { text: v.purl || '' },
            { text: v.severity || '', color: getSeverityColor(v.severity) }
        ]})
    }
    for (const v of (fc.resolvedVulnerabilities || [])) {
        rows.push({ cells: [
            { text: branchName },
            { text: version },
            { text: 'Resolved', color: getStatusColor('Resolved') },
            { text: 'Vulnerability' },
            { text: v.vulnId || '' },
            { text: v.purl || '' },
            { text: v.severity || '', color: getSeverityColor(v.severity) }
        ]})
    }
    for (const v of (fc.appearedViolations || [])) {
        rows.push({ cells: [
            { text: branchName },
            { text: version },
            { text: 'New', color: getStatusColor('New') },
            { text: 'Violation' },
            { text: v.type || '' },
            { text: v.purl || '' },
            { text: '' }
        ]})
    }
    for (const v of (fc.resolvedViolations || [])) {
        rows.push({ cells: [
            { text: branchName },
            { text: version },
            { text: 'Resolved', color: getStatusColor('Resolved') },
            { text: 'Violation' },
            { text: v.type || '' },
            { text: v.purl || '' },
            { text: '' }
        ]})
    }
    for (const w of (fc.appearedWeaknesses || [])) {
        rows.push({ cells: [
            { text: branchName },
            { text: version },
            { text: 'New', color: getStatusColor('New') },
            { text: 'Weakness' },
            { text: w.cweId || '' },
            { text: w.location || '' },
            { text: w.severity || '', color: getSeverityColor(w.severity) }
        ]})
    }
    for (const w of (fc.resolvedWeaknesses || [])) {
        rows.push({ cells: [
            { text: branchName },
            { text: version },
            { text: 'Resolved', color: getStatusColor('Resolved') },
            { text: 'Weakness' },
            { text: w.cweId || '' },
            { text: w.location || '' },
            { text: w.severity || '', color: getSeverityColor(w.severity) }
        ]})
    }
}

function buildFindingAggregatedTable(changelog: any): { headers: string[]; rows: PdfRow[]; widths: string[] } {
    const headers = ['Status', 'Type', 'Issue ID', 'PURL / Location', 'Severity', 'Attribution']
    const widths = ['auto', 'auto', 'auto', '*', 'auto', 'auto']
    const rows: PdfRow[] = []

    const fc = changelog.findingChanges
    if (!fc) return { headers, rows, widths }

    const allFindings: { finding: any; type: string; statusOrder: number }[] = []

    for (const v of (fc.vulnerabilities || [])) {
        const statusOrder = v.isNetAppeared ? 0 : v.isStillPresent ? 1 : v.isNetResolved ? 2 : 3
        allFindings.push({ finding: v, type: 'Vulnerability', statusOrder })
    }
    for (const v of (fc.violations || [])) {
        const statusOrder = v.isNetAppeared ? 0 : v.isStillPresent ? 1 : v.isNetResolved ? 2 : 3
        allFindings.push({ finding: v, type: 'Violation', statusOrder })
    }
    for (const w of (fc.weaknesses || [])) {
        const statusOrder = w.isNetAppeared ? 0 : w.isStillPresent ? 1 : w.isNetResolved ? 2 : 3
        allFindings.push({ finding: w, type: 'Weakness', statusOrder })
    }

    const severityOrder = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED']
    allFindings.sort((a, b) => {
        if (a.statusOrder !== b.statusOrder) return a.statusOrder - b.statusOrder
        const sevA = severityOrder.indexOf((a.finding.severity || 'UNASSIGNED').toUpperCase())
        const sevB = severityOrder.indexOf((b.finding.severity || 'UNASSIGNED').toUpperCase())
        return (sevA === -1 ? 999 : sevA) - (sevB === -1 ? 999 : sevB)
    })

    for (const { finding, type } of allFindings) {
        const status = finding.isNetAppeared ? 'New' : finding.isStillPresent ? 'Still Present' : finding.isNetResolved ? 'Resolved' : 'Unchanged'
        const issueId = type === 'Vulnerability' ? (finding.vulnId || '') : type === 'Weakness' ? (finding.cweId || '') : (finding.type || '')
        const purlOrLocation = finding.purl || finding.location || ''
        const severity = finding.severity || ''
        const attribution = formatFindingAttribution(finding)

        rows.push({ cells: [
            { text: status, color: getStatusColor(status) },
            { text: type },
            { text: issueId },
            { text: purlOrLocation },
            { text: severity, color: getSeverityColor(severity) },
            { text: attribution }
        ]})
    }

    return { headers, rows, widths }
}

// ==================== Attribution Helpers ====================

// Inline attribution arrays are preview-capped; the true total lives in *InCount.
// A static PDF cannot lazy-fetch, so append " +N more" to signal truncation.
function moreSuffix(count: number | undefined, shown: any[]): string {
    const more = Math.max(0, (count || 0) - (shown ? shown.length : 0))
    return more > 0 ? ` +${more} more` : ''
}

function formatAttribution(addedIn: any[], removedIn: any[], addedInCount?: number, removedInCount?: number): string {
    const parts: string[] = []
    if (addedIn && addedIn.length > 0) {
        parts.push('Added in: ' + addedIn.map((a: any) => `${a.componentName || ''}@${a.releaseVersion || ''}`).join(', ') + moreSuffix(addedInCount, addedIn))
    }
    if (removedIn && removedIn.length > 0) {
        parts.push('Removed in: ' + removedIn.map((a: any) => `${a.componentName || ''}@${a.releaseVersion || ''}`).join(', ') + moreSuffix(removedInCount, removedIn))
    }
    return parts.join('; ')
}

function formatFindingAttribution(finding: any): string {
    const parts: string[] = []
    if (finding.appearedIn && finding.appearedIn.length > 0) {
        parts.push(finding.appearedIn.map((a: any) => `${a.componentName || ''}@${a.releaseVersion || ''}`).join(', ') + moreSuffix(finding.appearedInCount, finding.appearedIn))
    }
    if (finding.resolvedIn && finding.resolvedIn.length > 0) {
        parts.push('Resolved: ' + finding.resolvedIn.map((a: any) => `${a.componentName || ''}@${a.releaseVersion || ''}`).join(', ') + moreSuffix(finding.resolvedInCount, finding.resolvedIn))
    }
    return parts.join('; ')
}

// ==================== Tab Label ====================

function tabLabel(tab: ChangelogTab): string {
    switch (tab) {
        case 'code': return 'Code Changes'
        case 'sbom': return 'SBOM Changes'
        case 'findings':
        case 'vulnerabilities': return 'Finding Changes'
        default: return 'Changes'
    }
}

// ==================== Main Export Function ====================

export function exportChangelogToPdf(options: ChangelogPdfOptions): { success: boolean; message?: string } {
    const {
        title,
        orgName,
        dateRange,
        aggregationType,
        activeTab,
        changelog,
        filenamePrefix = 'changelog'
    } = options

    if (!changelog) {
        return { success: false, message: 'No changelog data available to export' }
    }

    let tableData: { headers: string[]; rows: PdfRow[]; widths: string[] }

    switch (activeTab) {
        case 'code':
            tableData = buildCodeTable(changelog, aggregationType)
            break
        case 'sbom':
            tableData = buildSbomTable(changelog, aggregationType)
            break
        case 'findings':
        case 'vulnerabilities':
            tableData = buildFindingTable(changelog, aggregationType)
            break
        default:
            return { success: false, message: 'Unknown tab type' }
    }

    if (tableData.rows.length === 0) {
        return { success: false, message: `No ${tabLabel(activeTab).toLowerCase()} to export` }
    }

    // Build pdfmake table body
    const headerRow = tableData.headers.map(h => ({ text: h, style: 'tableHeader' }))
    const tableBody: any[][] = [headerRow]

    for (const row of tableData.rows) {
        const pdfRow = row.cells.map(cell => {
            const cellDef: any = { text: cell.text }
            if (cell.color) cellDef.color = cell.color
            if (cell.bold) cellDef.bold = true
            if (cell.colSpan) cellDef.colSpan = cell.colSpan
            if (cell.fillColor) cellDef.fillColor = cell.fillColor
            return cellDef
        })
        tableBody.push(pdfRow)
    }

    // Build subtitle lines
    const subtitleLines: any[] = [
        { text: `Organization: ${orgName || 'Unknown'}`, style: 'subheader' },
        { text: `Generated: ${new Date().toLocaleString('en-CA', { hour12: false })}`, style: 'subheader' }
    ]
    if (dateRange) {
        subtitleLines.push({ text: `Date Range: ${dateRange}`, style: 'subheader' })
    }
    subtitleLines.push(
        { text: `View: ${tabLabel(activeTab)} (${aggregationType})`, style: 'subheader' },
        { text: `Total rows: ${tableData.rows.length}`, style: 'subheader' },
        { text: 'Tool: ReARM - rearmhq.com', style: 'subheader', margin: [0, 0, 0, 10] }
    )

    const docDefinition: any = {
        pageOrientation: 'landscape',
        content: [
            { text: title, style: 'header' },
            ...subtitleLines,
            {
                table: {
                    headerRows: 1,
                    widths: tableData.widths,
                    body: tableBody
                },
                layout: {
                    fillColor: (rowIndex: number) => rowIndex === 0 ? '#f0f0f0' : null
                }
            }
        ],
        styles: {
            header: {
                fontSize: 16,
                bold: true,
                margin: [0, 0, 0, 5]
            },
            subheader: {
                fontSize: 10,
                color: '#666666',
                margin: [0, 0, 0, 3]
            },
            tableHeader: {
                bold: true,
                fontSize: 10,
                color: '#333333'
            }
        },
        defaultStyle: {
            fontSize: 9
        }
    }

    const timestamp = new Date().toISOString().slice(0, 10)
    const tabSuffix = activeTab === 'code' ? 'code' : activeTab === 'sbom' ? 'sbom' : 'finding-changes'
    const filename = `${filenamePrefix}-${tabSuffix}-${timestamp}.pdf`

    pdfMake.createPdf(docDefinition).download(filename)
    return { success: true }
}
