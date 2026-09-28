import type { DataTableColumns } from 'naive-ui'
import Swal from 'sweetalert2'
import { searchDtrackComponentByPurl } from '@/utils/dtrack'
import { Info20Regular } from '@vicons/fluent'
import { Edit, Eye } from '@vicons/tabler'
import { isSuppressedAnalysisState } from '@/constants/vulnAnalysis'
import { resolveKevCveId } from '@/utils/kevService'
import { ROW_SEVERITIES, emptySeverityCounts, findingTypeOf, renderFindingId, severityBucketOf } from '@/utils/findingUtils'
import { FindingType } from '@/constants/findingType'
import constants from '@/utils/constants'
import type { FindingComponentGroup } from '@/utils/findingGroups'
import type { FixedIn, VulnScore } from '@/utils/vulnerabilityRecordService'
import { bumpTargetsOf, bumpToText, bumpToTitle, fixedInText, fixedInTitle, isNoFix } from '@/utils/fixedInDisplay'
import {
  COMPUTED_FROM_VECTOR_TITLE,
  formatPrimaryScore,
  formatSubScores,
  isComputedFromVector,
  rowEpss,
  rowTopScore,
  scoreSortValue,
  summarizeScores
} from '@/utils/vulnScoreDisplay'

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
  analysisState?: string
  analysisDate?: string
  attributedAt?: string
  // CISA KEV flag: stamped post-fetch by kevService.annotateKnownExploited,
  // or carried straight from the main query via the inline knownExploited field.
  knownExploited?: boolean
  // Vulnerability rows only, and only when the query selected them (see
  // findingsQuery.ts): the org's record scores, the headline CVSS and EPSS.
  scores?: VulnScore[]
  topScore?: VulnScore | null
  epss?: VulnScore | null
  // Vulnerability rows only, when the query selected it: the advisory's fix
  // version for the row's package.
  fixedIn?: FixedIn | null
}

// Column a findings table opens sorted by: severity ascending (worst first),
// or a score column descending (highest first).
export type FindingSortKey = 'severity' | 'score' | 'epss'

// A findings table's sort: the column key and its order; order false = unsorted.
export interface FindingSortState {
  columnKey: string
  order: 'ascend' | 'descend' | false
}

/** The sort a findings table opens with for a FindingSortKey. */
export function openingFindingSort(key: FindingSortKey): FindingSortState {
  return key === 'severity' ? { columnKey: 'severity', order: 'ascend' } : { columnKey: key, order: 'descend' }
}

/**
 * The sort a naive-ui DataTable reports in update:sorter (single-column:
 * { columnKey, sorter, order }, order false once cleared; null from
 * clearSorter) as a FindingSortState.
 */
export function findingSortStateOf(sorter: { columnKey: string | number, order: 'ascend' | 'descend' | false } | null | undefined): FindingSortState {
  return sorter && sorter.order ? { columnKey: String(sorter.columnKey), order: sorter.order } : { columnKey: '', order: false }
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
        fingerprint: '-',
        analysisState: vuln.analysisState,
        analysisDate: vuln.analysisDate,
        attributedAt: vuln.attributedAt,
        knownExploited: !!vuln.knownExploited,
        scores: vuln.scores,
        topScore: vuln.topScore,
        epss: vuln.epss,
        fixedIn: vuln.fixedIn
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
        fingerprint: '-',
        analysisState: violation.analysisState,
        analysisDate: violation.analysisDate,
        attributedAt: violation.attributedAt
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
        fingerprint: weakness.fingerprint,
        analysisState: weakness.analysisState,
        analysisDate: weakness.analysisDate,
        attributedAt: weakness.attributedAt
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
    // Preferred deep-link: caller opens our native ReleaseSbomComponentGraph
    // modal for the clicked purl. When provided, this overrides the
    // Dependency-Track deep-link flow.
    onPurlClick?: (purl: string) => void
    onEditFinding?: (row: any) => void
    onViewAnalysis?: (row: any) => void
    // Opens the CISA KEV details modal for a KEV-flagged CVE (Pro only —
    // rows only carry knownExploited when the caller annotated them)
    onKevClick?: (cveId: string) => void
    // Opens the in-app details panel for a vulnerability id of the row (its own
    // id or an alias); without it vulnerability ids link to osv.dev.
    onVulnClick?: (vulnId: string, row: any) => void
    initialSeverityFilter?: string
    initialTypeFilter?: string | string[]
    // Controlled Type / Severity filters: when given, the columns show these
    // values (the caller keeps them from the table's update:filters) instead of
    // seeding their own from the initial* options.
    typeFilter?: () => string[]
    severityFilter?: () => string[]
    data?: any[]
    // Adds the Score and EPSS columns; set when the rows carry scores.
    showScores?: boolean
    // Adds the Fixed in column; set when the rows carry fix versions.
    showFixedIn?: boolean
    // Controlled sort, kept by the caller from the table's update:sorter;
    // without it the table sorts by severity on its own.
    sortState?: () => FindingSortState
  }
): DataTableColumns<any> {
  // Controlled, every sortable column carries its order (naive-ui ignores a
  // sortable column without one once any column is controlled). A sort on a
  // score column that is not shown falls back to the severity order.
  const sortOrderOf = (key: string): { sortOrder?: FindingSortState['order'], defaultSortOrder?: FindingSortState['order'] } => {
    if (!options?.sortState) return key === 'severity' ? { defaultSortOrder: 'ascend' } : {}
    let state = options.sortState()
    if (!options.showScores && (state.columnKey === 'score' || state.columnKey === 'epss')) state = openingFindingSort('severity')
    return { sortOrder: state.columnKey === key ? state.order : false }
  }
  const vulnClickFor = (row: any) => options?.onVulnClick
    ? (vulnId: string) => options.onVulnClick!(vulnId, row)
    : undefined

  function makePurlRenderer() {
    return (row: any) => {
      const purlText = row.purl || ''
      if (!purlText) return ''
      if (!purlText.startsWith('pkg:')) return purlText

      const onPurlClick = options?.onPurlClick
      if (onPurlClick) {
        return h('a', {
          href: '#',
          style: 'color: #337ab7; cursor: pointer; text-decoration: underline;',
          title: 'Open dependency graph for this purl',
          onClick: (e: Event) => {
            e.preventDefault()
            onPurlClick(purlText)
          }
        }, purlText)
      }

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
  // Calculate counts for filter options
  const data = options?.data || []
  const typeCounts: Record<string, number> = {
    'Vulnerability': data.filter(r => r.type === 'Vulnerability').length,
    'Violation': data.filter(r => r.type === 'Violation').length,
    'Weakness': data.filter(r => r.type === 'Weakness').length
  }
  const severityCounts = emptySeverityCounts()
  data.forEach(r => { severityCounts[severityBucketOf(r)]++ })

  return [
    {
      title: 'Type',
      key: 'type',
      width: 124,
      sorter: 'default',
      ...sortOrderOf('type'),
      filterOptions: [
        { label: `Vulnerability (${typeCounts['Vulnerability']})`, value: 'Vulnerability' },
        { label: `Violation (${typeCounts['Violation']})`, value: 'Violation' },
        { label: `Weakness (${typeCounts['Weakness']})`, value: 'Weakness' }
      ],
      ...(options?.typeFilter
        ? { filterOptionValues: options.typeFilter() }
        : { defaultFilterOptionValues: options?.initialTypeFilter ? (Array.isArray(options.initialTypeFilter) ? options.initialTypeFilter : [options.initialTypeFilter]) : [] }),
      filter: (value: any, row: any) => row.type === value,
      render: (row: any) => {
        const typeColors: any = {
          Vulnerability: 'error',
          Violation: 'warning',
          Weakness: 'info'
        }
        const isSuppressed = isSuppressedAnalysisState(row.analysisState)
        const tagStyle = isSuppressed ? { textDecoration: 'line-through' } : {}
        const typeTag = h(NTag, { 
          type: typeColors[row.type] || 'default', 
          size: 'small',
          style: tagStyle
        }, () => row.type)
        
        // Show info icon for any finding with an analysis state
        if (row.analysisState) {
          const tooltipContent = [
            `State: ${row.analysisState}`,
            h('br'),
            `Date: ${row.analysisDate ? new Date(row.analysisDate).toLocaleString('en-CA', {hour12: false}) : 'Unknown'}`
          ]
          
          return h('div', {}, [
            typeTag,
            h(NTooltip, {
              trigger: 'hover'
            }, {
              trigger: () => h(NIcon, {
                class: 'icons',
                size: 16,
                style: 'cursor: help; margin-left: 4px;'
              }, () => h(Info20Regular)),
              default: () => tooltipContent
            })
          ])
        }
        
        return typeTag
      }
    },
    {
      title: 'Issue ID',
      key: 'id',
      width: 130,
      render: (row: any) => {
        const id = String(row.id || '')
        if (!id) return ''
        const idLink = renderFindingId(h, id, findingTypeOf(row.type), vulnClickFor(row))
        if (!row.knownExploited) return idLink
        const kevTag = h(NTag, {
          type: 'error',
          size: 'small',
          bordered: false,
          title: 'CISA Known Exploited Vulnerability — click for details',
          style: options?.onKevClick ? 'cursor: pointer;' : '',
          onClick: () => options?.onKevClick?.(resolveKevCveId(row))
        }, { default: () => 'KEV' })
        return h('div', { style: 'display: flex; align-items: center; gap: 6px; flex-wrap: wrap;' }, [idLink, kevTag])
      }
    },
    { title: 'PURL or Location', key: 'purl', width: 400, ellipsis: { tooltip: true }, render: makePurlRenderer() },
    {
      title: 'Severity',
      key: 'severity',
      width: 140,
      ...sortOrderOf('severity'),
      filterOptions: [
        { label: `CRITICAL (${severityCounts['CRITICAL']})`, value: 'CRITICAL' },
        { label: `HIGH (${severityCounts['HIGH']})`, value: 'HIGH' },
        { label: `MEDIUM (${severityCounts['MEDIUM']})`, value: 'MEDIUM' },
        { label: `LOW (${severityCounts['LOW']})`, value: 'LOW' },
        { label: `UNASSIGNED (${severityCounts['UNASSIGNED']})`, value: 'UNASSIGNED' }
      ],
      ...(options?.severityFilter
        ? { filterOptionValues: options.severityFilter() }
        : { defaultFilterOptionValues: options?.initialSeverityFilter ? [options.initialSeverityFilter] : [] }),
      filter: (value: any, row: any) => severityBucketOf(row) === value,
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
              trigger: () => h(NIcon, {
                class: 'icons',
                size: 16,
                style: 'cursor: help;'
              }, () => h(Info20Regular)),
              default: () => severityTooltipContent
            })
          ])
        }
        
        return severityTag
      }
    },
    ...(options?.showScores ? scoreColumns(h, NTag, sortOrderOf) : []),
    ...(options?.showFixedIn ? [fixedInColumn(h, NTag)] : []),
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
          const aliasLinks = row.aliases.map((alias: any) =>
            renderFindingId(h, alias.aliasId, FindingType.VULNERABILITY, vulnClickFor(row)))
          elements.push(h('span', {}, ['Aliases: ', ...aliasLinks.reduce((acc: any[], link: any, index: number) => {
            if (index > 0) acc.push(', ')
            acc.push(link)
            return acc
          }, [])]))
        } else if (details && details !== '-') {
          elements.push(h('span', {}, details))
        }
        
        // Add fingerprint if it exists and is not empty or "-"
        if (row.fingerprint && row.fingerprint !== '-' && row.fingerprint.trim() !== '') {
          if (elements.length > 0) elements.push(h('span', {}, ', '))
          elements.push(h('span', {}, `Fingerprint: ${row.fingerprint}`))
        }
        
        // Add attributedAt if it exists
        if (row.attributedAt) {
          if (elements.length > 0) elements.push(h('br'))
          const formattedDate = new Date(row.attributedAt).toLocaleString('en-CA', {hour12: false})
          elements.push(h('span', {}, `Attributed: ${formattedDate}`))
        }
        
        return elements.length > 0 ? h('span', {}, elements) : '-'
      }
    },
    {
      title: 'Sources',
      key: 'sources',
      minWidth: 280,
      ellipsis: { tooltip: true },
      render: (row: any) => {
        if (!row.sources || row.sources.length === 0) return '-'
        
        const sourceElements = row.sources.map((source: any, index: number) => {
          const elements = []
          
          // Add release information with link if available
          if (elements.length > 0) elements.push(h('br'))
          if (source.release && source.releaseDetails && source.releaseDetails.componentDetails) {
            const hasArtifactType = source.artifactDetails?.type
            const releaseText = `${source.releaseDetails.componentDetails.name || 'Unknown'} ${source.releaseDetails.version || ''}${hasArtifactType ? ', ' : ''}`
            
            if (RouterLink) {
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
        const viewIcon = h(NIcon, {
          title: 'Analysis',
          class: 'icons clickable',
          size: 25,
          onClick: () => {
            if (options?.onViewAnalysis) {
              options.onViewAnalysis(row)
            }
          }
        }, () => h(Edit))
        
        return h('div', {}, [viewIcon])
      }
    }
  ]
}

// Score / EPSS cell: the formatted number with a native tooltip, '-' when
// the finding has none (violations, weaknesses, vulnerabilities without a
// record).
function scoreCell(h: any, sc: VulnScore | null, title: string, extra: any[] = []) {
  if (!sc) return '-'
  // The number never breaks; a tag after it wraps below when the column is narrow.
  return h('span', { title, style: 'display: inline-flex; flex-wrap: wrap; align-items: center; gap: 4px;' },
    [h('span', { style: 'white-space: nowrap;' }, formatPrimaryScore(sc)), ...extra])
}

// "Exploit probability 42.00%; Percentile 97.00%"
function epssTitle(epss: VulnScore): string {
  return [`Exploit probability ${formatPrimaryScore(epss)}`, formatSubScores(epss)].filter(Boolean).join('; ')
}

// The per-finding score columns, after Severity. Sorting puts rows without a
// score last when descending, the order the release header's pills open with.
function scoreColumns(h: any, NTag: any, sortOrderOf: (key: string) => object): DataTableColumns<any> {
  return [
    {
      title: 'Score',
      key: 'score',
      width: 140,
      ...sortOrderOf('score'),
      sorter: (rowA: any, rowB: any) => scoreSortValue(rowTopScore(rowA)) - scoreSortValue(rowTopScore(rowB)),
      render: (row: any) => {
        const top = rowTopScore(row)
        const computedTag = top && isComputedFromVector(top)
          ? [h(NTag, { size: 'tiny', bordered: false, title: COMPUTED_FROM_VECTOR_TITLE }, { default: () => 'computed' })]
          : []
        return scoreCell(h, top, summarizeScores(row.scores), computedTag)
      }
    },
    {
      title: 'EPSS',
      key: 'epss',
      width: 90,
      ...sortOrderOf('epss'),
      sorter: (rowA: any, rowB: any) => scoreSortValue(rowEpss(rowA)) - scoreSortValue(rowEpss(rowB)),
      render: (row: any) => {
        const epss = rowEpss(row)
        return scoreCell(h, epss, epss ? epssTitle(epss) : '')
      }
    }
  ]
}

// The advisory's fix version for the row's package; the hover text says why
// when there is none. No sorter: the UI has no version ordering.
function fixedInColumn(h: any, NTag: any) {
  return {
    title: 'Fixed in',
    key: 'fixedIn',
    // wide enough for a Debian security update (1:9.2p1-2+deb12u10) on one line
    width: 150,
    render: (row: any) => {
      const fixedIn: FixedIn | null | undefined = row.fixedIn
      if (!fixedIn) return '-'
      const title = fixedInTitle(fixedIn)
      if (isNoFix(fixedIn)) {
        return h(NTag, { size: 'small', type: 'warning', bordered: false, title }, { default: () => fixedInText(fixedIn) })
      }
      return h('span', { title, style: 'overflow-wrap: anywhere;' }, fixedInText(fixedIn))
    }
  }
}

// The group view's fix column: the distinct fix versions of the group's findings.
function bumpToColumn(h: any) {
  return {
    title: 'Bump to',
    key: 'bumpTo',
    width: 140,
    render: (group: FindingComponentGroup) => {
      const targets = bumpTargetsOf(group.rows)
      return h('span', { title: bumpToTitle(targets), style: 'overflow-wrap: anywhere;' }, bumpToText(targets))
    }
  }
}

// The group view's score columns: the highest headline CVSS and EPSS among
// the group's findings. Groups already sort worst first, so no sorter.
function groupScoreColumns(h: any): DataTableColumns<FindingComponentGroup> {
  return [
    {
      title: 'Worst score',
      key: 'worstScore',
      width: 110,
      render: (group: FindingComponentGroup) => scoreCell(h, group.worstScore, group.worstScore ? summarizeScores([group.worstScore]) : '')
    },
    {
      title: 'EPSS',
      key: 'maxEpss',
      width: 90,
      render: (group: FindingComponentGroup) => scoreCell(h, group.maxEpss, group.maxEpss ? epssTitle(group.maxEpss) : '')
    }
  ]
}

// Columns of the group-by-component view: one row per affected component, its
// findings table (the flat columns) nested in the expanded row.
export function buildComponentGroupColumns(
  h: any,
  NTag: any,
  NDataTable: any,
  options: {
    // Columns of the nested findings table; a getter so filter changes re-render it.
    findingColumns: () => DataTableColumns<any>
    rowKey: (row: any) => string
    // Forwarded from the nested tables so their filters and sort stay the view's.
    onUpdateFilters: (filters: Record<string, any>) => void
    onUpdateSorter?: (sorter: any) => void
    onPurlClick?: (purl: string) => void
    // Adds the Worst score and EPSS columns; set when the rows carry scores.
    showScores?: boolean
    // Adds the Bump to column; set when the rows carry fix versions.
    showFixedIn?: boolean
  }
): DataTableColumns<FindingComponentGroup> {
  const severityCircle = (severity: string, count: number) => h('span', {
    class: 'circle',
    style: { background: (constants.VulnerabilityColors as Record<string, string>)[severity] },
    title: `${count} ${severity.toLowerCase()}`
  }, String(count))

  return [
    {
      type: 'expand',
      renderExpand: (group: FindingComponentGroup) => h(NDataTable, {
        data: group.rows,
        columns: options.findingColumns(),
        rowKey: options.rowKey,
        pagination: group.rows.length > 10 ? { pageSize: 10 } : false,
        scrollX: 1400,
        size: 'small',
        'onUpdate:filters': options.onUpdateFilters,
        'onUpdate:sorter': options.onUpdateSorter
      })
    },
    {
      title: 'Component',
      key: 'label',
      minWidth: 320,
      render: (group: FindingComponentGroup) => {
        const parts: any[] = []
        if (group.ecosystem) {
          parts.push(h(NTag, { size: 'small', bordered: false, style: 'margin-right: 6px;' }, () => group.ecosystem))
        }
        const onPurlClick = options.onPurlClick
        parts.push(group.purl && onPurlClick
          ? h('a', {
            href: '#',
            title: `Open dependency graph for ${group.purl}`,
            onClick: (e: Event) => {
              e.preventDefault()
              onPurlClick(group.purl!)
            }
          }, group.label)
          : h('span', { title: group.purl || group.label }, group.label))
        return h('span', {}, parts)
      }
    },
    {
      title: 'Findings',
      key: 'findings',
      width: 280,
      render: (group: FindingComponentGroup) => {
        const circles = ROW_SEVERITIES
          .filter(s => group.severityCounts[s] > 0)
          .map(s => severityCircle(s, group.severityCounts[s]))
        if (group.violationCount > 0) {
          circles.push(h(NTag, { type: 'warning', size: 'small', bordered: false },
            () => `${group.violationCount} violation${group.violationCount === 1 ? '' : 's'}`))
        }
        return h('div', { style: 'display: flex; align-items: center; gap: 4px;' }, circles)
      }
    },
    ...(options.showScores ? groupScoreColumns(h) : []),
    ...(options.showFixedIn ? [bumpToColumn(h)] : []),
    {
      title: 'KEV',
      key: 'kevCount',
      width: 90,
      render: (group: FindingComponentGroup) => group.kevCount > 0
        ? h(NTag, { type: 'error', size: 'small', bordered: false, title: 'CISA Known Exploited Vulnerabilities' },
          () => `KEV ${group.kevCount}`)
        : ''
    },
    {
      title: 'Total',
      key: 'total',
      width: 80,
      render: (group: FindingComponentGroup) => String(group.rows.length)
    }
  ]
}
