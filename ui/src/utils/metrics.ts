import type { DataTableColumns } from 'naive-ui'

export type DetailedMetric = {
  type: 'Vulnerability' | 'Violation' | 'Weakness'
  id: string
  purl: string
  severity: string
  details: string
  location: string
  fingerprint: string
}

export function processMetricsData(metrics: any): DetailedMetric[] {
  const combinedData: DetailedMetric[] = []
  if (!metrics) return combinedData

  if (metrics.vulnerabilityDetails) {
    metrics.vulnerabilityDetails.forEach((vuln: any) => {
      combinedData.push({
        type: 'Vulnerability',
        id: vuln.vulnId,
        purl: vuln.purl,
        severity: vuln.severity,
        details: vuln.vulnId,
        location: '-',
        fingerprint: '-'
      })
    })
  }

  if (metrics.violationDetails) {
    metrics.violationDetails.forEach((violation: any) => {
      combinedData.push({
        type: 'Violation',
        id: violation.type,
        purl: violation.purl,
        severity: '-',
        details: `${violation.type}: ${violation.License || ''} ${violation.violationDetails || ''}`.trim(),
        location: '-',
        fingerprint: '-'
      })
    })
  }

  if (metrics.weaknessDetails) {
    metrics.weaknessDetails.forEach((weakness: any) => {
      combinedData.push({
        type: 'Weakness',
        id: weakness.cweId,
        purl: '-',
        severity: weakness.severity,
        details: weakness.ruleId,
        location: weakness.location,
        fingerprint: weakness.fingerprint
      })
    })
  }

  return combinedData
}

// Build shared vulnerability/violation/weakness columns
export function buildVulnerabilityColumns(h: any, NTag: any): DataTableColumns<any> {
  return [
    {
      title: 'Type',
      key: 'type',
      width: 120,
      sorter: 'default',
      render: (row: any) => {
        const typeColors: any = {
          Vulnerability: 'error',
          Violation: 'warning',
          Weakness: 'info'
        }
        return h(NTag, { type: typeColors[row.type] || 'default', size: 'small' }, { default: () => row.type })
      }
    },
    { title: 'Issue ID', key: 'id', width: 150 },
    { title: 'PURL', key: 'purl', width: 300, ellipsis: { tooltip: true } },
    {
      title: 'Severity',
      key: 'severity',
      width: 120,
      defaultSortOrder: 'ascend',
      sorter: (rowA: any, rowB: any) => {
        const order = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED', '-']
        const idx = (v: string) => {
          const i = order.indexOf(v)
          return i === -1 ? order.length : i
        }
        const diff = idx(rowA.severity) - idx(rowB.severity)
        if (diff !== 0) return diff
        // tie-breaker by Type (alphabetical, same as Type's default sorter)
        return String(rowA.type || '').localeCompare(String(rowB.type || ''))
      },
      render: (row: any) => {
        if (row.severity === '-') return row.severity
        const severityColors: any = {
          CRITICAL: 'error',
          HIGH: 'error',
          MEDIUM: 'warning',
          LOW: 'info',
          UNASSIGNED: 'default'
        }
        return h(NTag, { type: severityColors[row.severity] || 'default', size: 'small' }, { default: () => row.severity })
      }
    },
    { title: 'Details', key: 'details', ellipsis: { tooltip: true } },
    { title: 'Location', key: 'location', width: 200, ellipsis: { tooltip: true } },
    { title: 'Fingerprint', key: 'fingerprint', width: 200, ellipsis: { tooltip: true } }
  ]
}
