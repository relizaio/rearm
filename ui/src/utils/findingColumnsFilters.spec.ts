import { describe, it, expect, vi } from 'vitest'
import { groupFindingsByComponent } from './findingGroups'
import { severityBucketOf } from './findingUtils'

// metrics.ts reaches the GraphQL client, which starts Keycloak on import; these
// column builders never query, so a stub client is enough.
vi.mock('@/utils/graphql', () => ({ default: {} }))

const { buildVulnerabilityColumns, buildComponentGroupColumns, findingSortStateOf, openingFindingSort, processMetricsData } = await import('./metrics')

// A stand-in for Vue's h() and the naive-ui components the builders take.
const h = (tag: any, props: any, children: any) => ({ tag, props, children })
const Stub = {}

const column = (columns: any[], key: string) => columns.find(c => c.key === key)

describe('findings table filters shared by the flat and grouped views', () => {
    it('lets the caller control the Type and Severity filters', () => {
        let severity = ['HIGH']
        const columns = buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, {
            typeFilter: () => ['Vulnerability', 'Weakness'],
            severityFilter: () => severity,
            data: []
        }) as any[]
        expect(column(columns, 'type').filterOptionValues).toEqual(['Vulnerability', 'Weakness'])
        expect(column(columns, 'severity').filterOptionValues).toEqual(['HIGH'])
        expect(column(columns, 'severity').defaultFilterOptionValues).toBeUndefined()
        severity = []
        const rebuilt = buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, { severityFilter: () => severity, data: [] }) as any[]
        expect(column(rebuilt, 'severity').filterOptionValues).toEqual([])
    })

    it('still seeds its own filters when the caller does not control them', () => {
        const columns = buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, {
            initialSeverityFilter: 'CRITICAL', initialTypeFilter: 'Vulnerability', data: []
        }) as any[]
        expect(column(columns, 'severity').defaultFilterOptionValues).toEqual(['CRITICAL'])
        expect(column(columns, 'type').defaultFilterOptionValues).toEqual(['Vulnerability'])
        expect(column(columns, 'severity').filterOptionValues).toBeUndefined()
    })

    it('filters severity by the same bucket the grouped view counts', () => {
        const rows = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED', '-', '', undefined, 'INFO'].map(severity => ({ severity }))
        const severityColumn = column(buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, { data: rows as any[] }) as any[], 'severity')
        for (const option of ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED']) {
            for (const r of rows) {
                expect(severityColumn.filter(option, r), `${option} vs ${r.severity}`).toBe(severityBucketOf(r) === option)
            }
        }
        // The option labels count the same buckets.
        expect(severityColumn.filterOptions.map((o: any) => o.label))
            .toEqual(['CRITICAL (1)', 'HIGH (1)', 'MEDIUM (1)', 'LOW (1)', 'UNASSIGNED (5)'])
    })

    it('nests the flat findings columns under each component and forwards their filter changes', () => {
        const findingColumns = [{ key: 'id' }]
        const onUpdateFilters = () => {}
        const rowKey = (r: any) => r.id
        const columns = buildComponentGroupColumns(h, Stub, Stub, { findingColumns: () => findingColumns as any, rowKey, onUpdateFilters }) as any[]
        const [group] = groupFindingsByComponent([{ type: 'Vulnerability', id: 'CVE-1', purl: 'pkg:npm/a@1', severity: 'HIGH' } as any])
        const nested = columns[0].renderExpand(group)
        expect(nested.props).toMatchObject({ data: group.rows, columns: findingColumns, rowKey, pagination: false })
        expect(nested.props['onUpdate:filters']).toBe(onUpdateFilters)
    })
})

describe('score columns and the findings sort', () => {
    const cvss = (n: number, scoreSource?: string) => ({ type: 'CVSS_V3', score: n, subScores: [], scoreSource })
    const epss = (n: number) => ({ type: 'EPSS', score: n, subScores: [{ type: 'PERCENTILE', value: 0.97 }] })
    const keys = (columns: any[]) => columns.map(c => c.key)

    it('adds Score and EPSS after Severity only when the rows carry scores', () => {
        expect(keys(buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, { data: [] }) as any[])).not.toContain('score')
        const scored = keys(buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, { data: [], showScores: true }) as any[])
        expect(scored.slice(scored.indexOf('severity'), scored.indexOf('severity') + 3)).toEqual(['severity', 'score', 'epss'])
    })

    it('renders the headline score with a computed tag, EPSS as a percentage, and - without one', () => {
        const columns = buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, { data: [], showScores: true }) as any[]
        const scoreCell: any = column(columns, 'score').render({ topScore: cvss(9.8, 'COMPUTED_FROM_VECTOR'), scores: [cvss(9.8)] })
        expect(scoreCell.children[0].children).toBe('9.8')
        expect(scoreCell.children[1].children.default()).toBe('computed')
        expect(scoreCell.props.title).toBe('CVSS v3 9.8')
        expect(column(columns, 'score').render({ topScore: cvss(5.3), scores: [] }).children).toHaveLength(1)
        expect(column(columns, 'score').render({ type: 'Violation' })).toBe('-')
        const epssCell: any = column(columns, 'epss').render({ epss: epss(0.42) })
        expect(epssCell.children[0].children).toBe('42.00%')
        expect(epssCell.props.title).toBe('Exploit probability 42.00%; Percentile 97.00%')
    })

    it('sorts by score with unscored rows lowest', () => {
        const sorter = column(buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, { data: [], showScores: true }) as any[], 'score').sorter
        const rows = [{ id: 'none' }, { id: 'hi', topScore: cvss(9.1) }, { id: 'zero', topScore: cvss(0) }, { id: 'mid', topScore: cvss(5) }]
        expect([...rows].sort(sorter).map(r => r.id)).toEqual(['none', 'zero', 'mid', 'hi'])
    })

    it('opens on severity by itself, and on the caller\'s column when the caller holds the sort', () => {
        const own = buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, { data: [], showScores: true }) as any[]
        expect(column(own, 'severity').defaultSortOrder).toBe('ascend')
        expect(column(own, 'score').sortOrder).toBeUndefined()

        const byScore = buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, {
            data: [], showScores: true, sortState: () => openingFindingSort('score')
        }) as any[]
        expect(column(byScore, 'score').sortOrder).toBe('descend')
        // Controlled: every sortable column states its order, or naive-ui drops its sort.
        expect(column(byScore, 'severity').sortOrder).toBe(false)
        expect(column(byScore, 'type').sortOrder).toBe(false)
        expect(column(byScore, 'epss').sortOrder).toBe(false)
    })

    it('falls back to the severity order when a score sort is asked for rows without scores', () => {
        const columns = buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, {
            data: [], showScores: false, sortState: () => openingFindingSort('epss')
        }) as any[]
        expect(column(columns, 'severity').sortOrder).toBe('ascend')
        expect(column(columns, 'epss')).toBeUndefined()
        expect(openingFindingSort('severity')).toEqual({ columnKey: 'severity', order: 'ascend' })
    })

    it('reads the table\'s reported sort, a cleared one as unsorted', () => {
        expect(findingSortStateOf({ columnKey: 'score', order: 'descend' })).toEqual({ columnKey: 'score', order: 'descend' })
        expect(findingSortStateOf({ columnKey: 'score', order: false })).toEqual({ columnKey: '', order: false })
        expect(findingSortStateOf(null)).toEqual({ columnKey: '', order: false })
    })

    it('adds the group score columns and forwards the nested tables\' sort', () => {
        const onUpdateSorter = () => {}
        const columns = buildComponentGroupColumns(h, Stub, Stub, {
            findingColumns: () => [], rowKey: (r: any) => r.id, onUpdateFilters: () => {}, onUpdateSorter, showScores: true
        }) as any[]
        expect(keys(columns)).toEqual([undefined, 'label', 'findings', 'worstScore', 'maxEpss', 'kevCount', 'total'])
        const [group] = groupFindingsByComponent([{ type: 'Vulnerability', id: 'CVE-1', purl: 'pkg:npm/a@1', severity: 'HIGH', topScore: cvss(8.1), epss: epss(0.3) } as any])
        expect((column(columns, 'worstScore').render(group) as any).children[0].children).toBe('8.1')
        expect((column(columns, 'maxEpss').render(group) as any).children[0].children).toBe('30.00%')
        expect(columns[0].renderExpand(group).props['onUpdate:sorter']).toBe(onUpdateSorter)
        expect(keys(buildComponentGroupColumns(h, Stub, Stub, {
            findingColumns: () => [], rowKey: (r: any) => r.id, onUpdateFilters: () => {}
        }) as any[])).not.toContain('worstScore')
    })

    it('copies the score fields onto vulnerability rows, leaving them unset when not selected', () => {
        const [scored] = processMetricsData({ vulnerabilityDetails: [{ vulnId: 'CVE-1', scores: [cvss(7)], topScore: cvss(7), epss: null }] })
        expect(scored).toMatchObject({ scores: [cvss(7)], topScore: cvss(7), epss: null })
        const [plain] = processMetricsData({ vulnerabilityDetails: [{ vulnId: 'CVE-2' }] })
        expect(plain.scores).toBeUndefined()
    })
})

describe('the fix version columns', () => {
    const keys = (columns: any[]) => columns.map(c => c.key)
    const fixedIn = (verdict: string, extra: any = {}) => ({ verdict, sources: ['OSV'], ...extra })
    const vuln = (id: string, fix: any) => ({ type: 'Vulnerability', id, purl: 'pkg:pypi/django@1.11', severity: 'HIGH', fixedIn: fix } as any)

    it('adds Fixed in after the score columns only when the rows carry fix versions', () => {
        expect(keys(buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, { data: [], showScores: true }) as any[])).not.toContain('fixedIn')
        const withFix = keys(buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, { data: [], showScores: true, showFixedIn: true }) as any[])
        expect(withFix.slice(withFix.indexOf('epss'), withFix.indexOf('epss') + 2)).toEqual(['epss', 'fixedIn'])
    })

    it('renders the version, the last affected one, a no-fix tag, or a dash with the reason', () => {
        const render = column(buildVulnerabilityColumns(h, Stub, Stub, Stub, undefined, { data: [], showFixedIn: true }) as any[], 'fixedIn').render
        const fixed: any = render({ fixedIn: fixedIn('FIXED_IN', { version: '1.11.11' }) })
        expect(fixed.children).toBe('1.11.11')
        expect(fixed.props.title).toMatch(/fixes the finding \(OSV\)$/)
        expect(render({ fixedIn: fixedIn('FIXED_AFTER', { endIncluding: '2.0' }) }).children).toBe('> 2.0')
        const noFix: any = render({ fixedIn: fixedIn('NO_FIX_AVAILABLE') })
        expect(noFix.children.default()).toBe('no fix')
        const outside: any = render({ fixedIn: fixedIn('NOT_IN_ADVISORY_RANGE') })
        expect(outside.children).toBe('-')
        expect(outside.props.title).toMatch(/matched by name/i)
        expect(render({ type: 'Violation' })).toBe('-')
    })

    it('lists a component\'s distinct fix versions as its bump targets, a count past three', () => {
        const columns = buildComponentGroupColumns(h, Stub, Stub, {
            findingColumns: () => [], rowKey: (r: any) => r.id, onUpdateFilters: () => {}, showFixedIn: true
        }) as any[]
        expect(keys(columns)).toContain('bumpTo')
        const [two] = groupFindingsByComponent([
            vuln('A', fixedIn('FIXED_IN', { version: '1.11.11' })),
            vuln('B', fixedIn('NO_FIX_AVAILABLE')),
            vuln('C', fixedIn('FIXED_IN', { version: '1.11.5' })),
            vuln('D', fixedIn('FIXED_IN', { version: '1.11.11' }))])
        expect((column(columns, 'bumpTo').render(two) as any).children).toBe('1.11.11, 1.11.5')
        const [four] = groupFindingsByComponent(['1', '2', '3', '4'].map(v => vuln(v, fixedIn('FIXED_IN', { version: `1.11.${v}` }))))
        const many: any = column(columns, 'bumpTo').render(four)
        expect(many.children).toBe('4 targets')
        expect(many.props.title).toContain('1.11.1, 1.11.2, 1.11.3, 1.11.4')
        const [none] = groupFindingsByComponent([vuln('E', fixedIn('NO_RANGE_DATA'))])
        expect((column(columns, 'bumpTo').render(none) as any).children).toBe('-')
        expect(keys(buildComponentGroupColumns(h, Stub, Stub, {
            findingColumns: () => [], rowKey: (r: any) => r.id, onUpdateFilters: () => {}
        }) as any[])).not.toContain('bumpTo')
    })

    it('copies the fix version onto vulnerability rows, leaving it unset when not selected', () => {
        const [fixed] = processMetricsData({ vulnerabilityDetails: [{ vulnId: 'CVE-1', fixedIn: fixedIn('FIXED_IN', { version: '2' }) }] })
        expect(fixed.fixedIn).toEqual(fixedIn('FIXED_IN', { version: '2' }))
        const [plain] = processMetricsData({ vulnerabilityDetails: [{ vulnId: 'CVE-2' }] })
        expect(plain.fixedIn).toBeUndefined()
    })
})
