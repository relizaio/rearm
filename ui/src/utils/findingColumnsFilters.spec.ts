import { describe, it, expect, vi } from 'vitest'
import { groupFindingsByComponent } from './findingGroups'
import { severityBucketOf } from './findingUtils'

// metrics.ts reaches the GraphQL client, which starts Keycloak on import; these
// column builders never query, so a stub client is enough.
vi.mock('@/utils/graphql', () => ({ default: {} }))

const { buildVulnerabilityColumns, buildComponentGroupColumns } = await import('./metrics')

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
