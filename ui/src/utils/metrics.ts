import type { DataTableColumns } from 'naive-ui'
import Swal from 'sweetalert2'
import { searchDtrackComponentByPurl } from '@/utils/dtrack'

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
export function buildVulnerabilityColumns(
  h: any,
  NTag: any,
  options?: {
    hasKnownDependencyTrackIntegration?: () => boolean
    getArtifacts?: () => any[]
    getOrgUuid?: () => string
    getDtrackProjectUuids?: () => string[]
  }
): DataTableColumns<any> {
  function makePurlRenderer() {
    return (row: any) => {
      const purlText = row.purl || ''
      if (!purlText) return ''
      if (!options || !options.hasKnownDependencyTrackIntegration || !options.hasKnownDependencyTrackIntegration()) {
        return purlText
      }
      return h('a', {
        href: '#',
        style: 'color: #337ab7; cursor: pointer; text-decoration: underline;',
        onClick: async (e: Event) => {
          e.preventDefault()
          try {
            const orgUuid = options.getOrgUuid ? options.getOrgUuid() : ''
            const dtrackProjects = options.getDtrackProjectUuids ? options.getDtrackProjectUuids() : []
            const dtrackComponent = await searchDtrackComponentByPurl(orgUuid, purlText, dtrackProjects)
            if (dtrackComponent) {
              const artifacts = options.getArtifacts ? options.getArtifacts() : []
              const firstArtifactWithDtrack = artifacts.find((artifact: any) => artifact.metrics && artifact.metrics.dependencyTrackFullUri)
              if (firstArtifactWithDtrack) {
                const baseUrl = firstArtifactWithDtrack.metrics.dependencyTrackFullUri.split('/projects')[0]
                const componentUrl = `${baseUrl}/components/${dtrackComponent}`
                window.open(componentUrl, '_blank')
                return
              }
            }
            await Swal.fire({
              icon: 'warning',
              title: 'Not Found',
              text: 'Purl not found in known SBOMs',
              timer: 2500,
              showConfirmButton: false
            })
          } catch (err) {
            // best-effort UI message
            await Swal.fire({
              icon: 'error',
              title: 'Error',
              text: 'Unable to open Dependency-Track component link',
              timer: 2500,
              showConfirmButton: false
            })
          }
        }
      }, purlText)
    }
  }
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
    {
      title: 'Issue ID',
      key: 'id',
      width: 150,
      render: (row: any) => {
        const id = String(row.id || '')
        if (!id) return ''
        const confirmAndOpen = async (e: Event, href: string) => {
          e.preventDefault()
          try {
            const LS_KEY = 'rearm_external_link_consent_until'
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
              // result.value === 1 when checkbox is checked
              if (result.value === 1) {
                const fifteenDaysMs = 15 * 24 * 60 * 60 * 1000
                localStorage.setItem(LS_KEY, String(now + fifteenDaysMs))
              }
              window.open(href, '_blank')
            }
          } catch (err) {
            // Fail open on errors to avoid blocking navigation unexpectedly
            window.open(href, '_blank')
          }
        }
        if (id.startsWith('CVE-')) {
          const href = `https://osv.dev/vulnerability/${id}`
          return h('a', {
            href,
            target: '_blank',
            rel: 'noopener noreferrer',
            onClick: (e: Event) => confirmAndOpen(e, href)
          }, id)
        }
        if (id.startsWith('CWE-')) {
          const raw = id.slice(4)
          const num = String(parseInt(raw, 10))
          if (num && num !== 'NaN') {
            const href = `https://cwe.mitre.org/data/definitions/${num}.html`
            return h('a', {
              href,
              target: '_blank',
              rel: 'noopener noreferrer',
              onClick: (e: Event) => confirmAndOpen(e, href)
            }, id)
          }
        }
        return id
      }
    },
    { title: 'PURL', key: 'purl', width: 300, ellipsis: { tooltip: true }, render: makePurlRenderer() },
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
