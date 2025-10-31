import type { DataTableColumns } from 'naive-ui'
import Swal from 'sweetalert2'
import { searchDtrackComponentByPurl } from '@/utils/dtrack'
import { Info20Regular } from '@vicons/fluent'
import { Edit } from '@vicons/tabler'

export type DetailedMetric = {
  type: 'Vulnerability' | 'Violation' | 'Weakness'
  id: string
  purl: string
  severity: string
  details: string
  location: string
  fingerprint: string
  aliases?: any[]
  severities?: any[]
  sources?: any[]
}

// Helper function to create vulnerability links with confirmation dialog
function createVulnerabilityLink(h: any, id: string) {
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
  
  if (id.startsWith('ALPINE-CVE-') || id.startsWith('CVE-') || id.startsWith('GHSA-')) {
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
        details: '-',
        aliases: vuln.aliases,
        severities: vuln.severities,
        sources: vuln.sources,
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
        details: violation.type === 'LICENSE' ? `License: ${violation.license}` : `${violation.type}: ${violation.violationDetails || ''}`,
        sources: violation.sources,
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
        purl: weakness.location,
        severity: weakness.severity,
        details: weakness.ruleId,
        sources: weakness.sources,
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
  NTooltip: any,
  NIcon: any,
  RouterLink?: any,
  options?: {
    hasKnownDependencyTrackIntegration?: () => boolean
    getArtifacts?: () => any[]
    getOrgUuid?: () => string
    getDtrackProjectUuids?: () => string[]
    onEditFinding?: (row: any) => void
  }
): DataTableColumns<any> {
  function makePurlRenderer() {
    return (row: any) => {
      const purlText = row.purl || ''
      if (!purlText) return ''
      if (!options || !options.hasKnownDependencyTrackIntegration || !options.hasKnownDependencyTrackIntegration() || !purlText.startsWith('pkg:')) {
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
              text: 'Purl not found in known SBOMs'
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
      width: 100,
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
      width: 130,
      render: (row: any) => {
        const id = String(row.id || '')
        if (!id) return ''
        return createVulnerabilityLink(h, id)
      }
    },
    { title: 'PURL or Location', key: 'purl', width: 400, ellipsis: { tooltip: true }, render: makePurlRenderer() },
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
        
        const severityTag = h(NTag, { type: severityColors[row.severity] || 'default', size: 'small' }, { default: () => row.severity })
        
        // Check if severities array exists and has multiple entries
        if (row.severities && row.severities.length > 0) {
          // Sort severities: NVD first, then GHSA, then OTHER
          const sortedSeverities = [...row.severities].sort((a: any, b: any) => {
            const order = ['NVD', 'GHSA', 'OTHER']
            const aIndex = order.indexOf(a.source)
            const bIndex = order.indexOf(b.source)
            return (aIndex === -1 ? order.length : aIndex) - (bIndex === -1 ? order.length : bIndex)
          })
          
          const severityTooltipContent = sortedSeverities.map((sev: any, index: number) => {
            const line = `${sev.source}: ${sev.severity}`
            return index < sortedSeverities.length - 1 
              ? [line, h('br')] 
              : line
          }).flat()
          
          return h('div', { }, [
            severityTag,
            h(NTooltip, {
              trigger: 'hover'
            }, {
              trigger: () => {
                return h(NIcon, {
                  class: 'icons',
                  size: 16,
                  style: 'cursor: help;'
                }, { default: () => h(Info20Regular) })
              },
              default: () => severityTooltipContent
            })
          ])
        }
        
        return severityTag
      }
    },
    { 
      title: 'Details', 
      key: 'details', 
      minWidth: 200,
      ellipsis: { tooltip: true },
      render: (row: any) => {
        const elements = []
        let details = row.details || ''
        
        // Add aliases if present for vulnerabilities
        if (row.type === 'Vulnerability' && row.aliases && row.aliases.length > 0) {
          const aliasLinks = row.aliases.map((alias: any) => createVulnerabilityLink(h, alias.aliasId))
          elements.push(h('span', {}, ['Aliases: ', ...aliasLinks.reduce((acc: any[], link: any, index: number) => {
            if (index > 0) acc.push(', ')
            acc.push(link)
            return acc
          }, [])]))
        } else if (details) {
          elements.push(h('span', {}, details))
        }
        
        // Add fingerprint if it exists and is not empty or "-"
        if (row.fingerprint && row.fingerprint !== '-' && row.fingerprint.trim() !== '') {
          if (elements.length > 0) elements.push(h('span', {}, ', '))
          elements.push(h('span', {}, `Fingerprint: ${row.fingerprint}`))
        }
        
        return elements.length > 0 ? h('span', {}, elements) : '-'
      }
    },
    {
      title: 'Sources',
      key: 'sources',
      width: 280,
      ellipsis: { tooltip: true },
      render: (row: any) => {
        if (!row.sources || row.sources.length === 0) return '-'
        
        const sourceElements = row.sources.map((source: any, index: number) => {
          const elements = []
          
          // Add release information with link if available
          if (elements.length > 0) elements.push(h('br'))
          if (source.release && source.releaseDetails && source.releaseDetails.componentDetails) {
            const releaseText = `${source.releaseDetails.componentDetails.name || 'Unknown'} ${source.releaseDetails.version || ''}, `
            
            if (RouterLink && options?.getOrgUuid) {
              const releaseLink = h(RouterLink, {
                to: {
                  name: 'ReleaseView',
                  params: {
                    uuid: source.release
                  }
                },
                style: 'text-decoration: none; color: #0066cc;'
              }, () => releaseText)
              elements.push(releaseLink)
            } else {
              elements.push(h('span', {}, releaseText))
            }
          }
          
          // Add artifact type information
          if (source.artifactDetails?.type) {
            elements.push(h('span', {}, `${source.artifactDetails.type}`))
          }
          
          // Add separator between sources (except for the last one)
          if (index < row.sources.length - 1) {
            elements.push(h('hr', { style: 'margin: 8px 0; border: none; border-top: 1px solid #eee;' }))
          }
          
          return elements
        }).flat()
        
        return h('div', {}, sourceElements)
      }
    },
    {
      title: 'Actions',
      key: 'actions',
      width: 80,
      render: (row: any) => {
        const editIcon = h(NIcon, {
          title: 'Create Analysis',
          class: 'icons clickable',
          size: 25,
          onClick: () => {
            if (options?.onEditFinding) {
              options.onEditFinding(row)
            }
          }
        }, { default: () => h(Edit) })
        
        return h('div', {}, [editIcon])
      }
    }
  ]
}
