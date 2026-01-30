import pdfMake from 'pdfmake/build/pdfmake'
import pdfFonts from 'pdfmake/build/vfs_fonts'
import constants from '@/utils/constants'

pdfMake.vfs = pdfFonts.vfs

export interface PdfExportOptions {
    data: any[]
    title: string
    orgName: string
    types?: string[]
    severities?: string[]
    includeAnalysis?: boolean
    includeSuppressed?: boolean
    filenamePrefix?: string
}

function getSeverityColor(severity: string): string {
    const colors = constants.VulnerabilityColors
    switch (severity) {
        case 'CRITICAL':
            return colors.CRITICAL
        case 'HIGH':
            return colors.HIGH
        case 'MEDIUM':
            return colors.MEDIUM
        case 'LOW':
            return colors.LOW
        default:
            return colors.UNASSIGNED
    }
}

export function exportFindingsToPdf(options: PdfExportOptions): { success: boolean; message?: string } {
    const {
        data: rawData,
        title,
        orgName,
        types = ['Vulnerability', 'Violation', 'Weakness'],
        severities = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED'],
        includeAnalysis = true,
        includeSuppressed = false,
        filenamePrefix = 'findings'
    } = options

    // Filter data based on settings
    let data = rawData.filter((row: any) => {
        // Filter by type
        if (!types.includes(row.type)) return false
        // Filter by severity
        const severity = row.severity || 'UNASSIGNED'
        if (!severities.includes(severity)) return false
        // Filter suppressed
        if (!includeSuppressed) {
            const state = row.analysisState
            if (state === 'FALSE_POSITIVE' || state === 'NOT_AFFECTED') return false
        }
        return true
    })

    if (!data || data.length === 0) {
        return { success: false, message: 'No findings match the selected filters' }
    }

    // Sort data: Type (Vulnerability, Weakness, Violation), then Severity, then PURL/Location
    const typeOrder = ['Vulnerability', 'Weakness', 'Violation']
    const severityOrder = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED']
    
    data = [...data].sort((a: any, b: any) => {
        // Sort by type first
        const typeA = typeOrder.indexOf(a.type)
        const typeB = typeOrder.indexOf(b.type)
        if (typeA !== typeB) return (typeA === -1 ? 999 : typeA) - (typeB === -1 ? 999 : typeB)
        
        // Then by severity
        const sevA = severityOrder.indexOf(a.severity || 'UNASSIGNED')
        const sevB = severityOrder.indexOf(b.severity || 'UNASSIGNED')
        if (sevA !== sevB) return (sevA === -1 ? 999 : sevA) - (sevB === -1 ? 999 : sevB)
        
        // Then by PURL/Location
        const purlA = (a.purl || a.location || '').toLowerCase()
        const purlB = (b.purl || b.location || '').toLowerCase()
        return purlA.localeCompare(purlB)
    })

    // Build table header based on includeAnalysis setting
    const headerRow: any[] = [
        { text: 'Type', style: 'tableHeader' },
        { text: 'Issue ID', style: 'tableHeader' },
        { text: 'PURL / Location', style: 'tableHeader' },
        { text: 'Severity', style: 'tableHeader' }
    ]
    if (includeAnalysis) {
        headerRow.push({ text: 'Analysis State', style: 'tableHeader' })
    }

    const tableBody: any[][] = [headerRow]

    // Add data rows
    data.forEach((row: any) => {
        const isSuppressedRow = row.analysisState === 'FALSE_POSITIVE' || row.analysisState === 'NOT_AFFECTED'
        const rowStyle = isSuppressedRow ? { decoration: 'lineThrough', color: '#888888' } : {}
        
        const rowData: any[] = [
            { text: row.type || '', ...rowStyle },
            { text: row.id || '', ...rowStyle },
            { text: row.purl || row.location || '', ...rowStyle },
            { text: row.severity || '', ...rowStyle, color: getSeverityColor(row.severity) }
        ]
        if (includeAnalysis) {
            rowData.push({ text: row.analysisState || 'In Triage', ...rowStyle })
        }
        tableBody.push(rowData)
    })

    // Set column widths based on includeAnalysis
    const widths = includeAnalysis 
        ? ['auto', 'auto', '*', 'auto', 'auto']
        : ['auto', 'auto', '*', 'auto']

    const docDefinition: any = {
        pageOrientation: 'landscape',
        content: [
            { text: title, style: 'header' },
            { text: `Organization: ${orgName || 'Unknown'}`, style: 'subheader' },
            { text: `Generated: ${new Date().toLocaleString('en-CA', { hour12: false })}`, style: 'subheader' },
            { text: `Total findings: ${data.length}${includeSuppressed ? ' (including suppressed)' : ''}`, style: 'subheader' },
            { text: `Tool: ReARM (https://rearmhq.com)`, style: 'subheader', margin: [0, 0, 0, 10] },
            {
                table: {
                    headerRows: 1,
                    widths: widths,
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

    // Generate filename
    const timestamp = new Date().toISOString().slice(0, 10)
    const filename = `${filenamePrefix}-${timestamp}.pdf`

    pdfMake.createPdf(docDefinition).download(filename)
    return { success: true }
}
