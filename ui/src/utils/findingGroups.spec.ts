import { describe, it, expect, vi, afterEach } from 'vitest'
import {
    componentCountOf,
    groupFindingsByComponent,
    initialColumnFilters,
    matchesFindingFilters,
    purlComponentIdentity,
    storedGroupByComponent,
    storeGroupByComponent
} from './findingGroups'
import { severityBucketOf } from './findingUtils'
import type { DetailedMetric } from './metrics'

function row (partial: Partial<DetailedMetric> & Pick<DetailedMetric, 'type' | 'id'>): DetailedMetric {
    return { purl: '', severity: 'UNASSIGNED', details: '-', location: '-', fingerprint: '-', ...partial } as DetailedMetric
}

const GLIBC = 'pkg:deb/debian/glibc@2.36-9+deb12u14?distro=debian-12'
const LOG4J = 'pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1?type=jar'

describe('grouping findings by component', () => {
    it('keys a component by type/namespace/name@version, whatever its qualifiers or encoding', () => {
        const key = (purl: string) => purlComponentIdentity(purl).key
        expect(key(GLIBC)).toBe('pkg:deb/debian/glibc@2.36-9+deb12u14')
        expect(key('pkg:deb/debian/glibc@2.36-9%2Bdeb12u14?distro=debian-12&arch=amd64'))
            .toBe('pkg:deb/debian/glibc@2.36-9+deb12u14')
        expect(key('pkg:npm/%40babel/traverse@7.22.0#lib')).toBe('pkg:npm/@babel/traverse@7.22.0')
        expect(purlComponentIdentity('pkg:deb/debian/zlib@1:1.2.13.dfsg-1?distro=debian-12'))
            .toEqual({ key: 'pkg:deb/debian/zlib@1:1.2.13.dfsg-1', label: 'debian/zlib@1:1.2.13.dfsg-1', ecosystem: 'deb' })
        // No namespace, no version.
        expect(purlComponentIdentity('pkg:npm/lodash')).toEqual({ key: 'pkg:npm/lodash', label: 'lodash', ecosystem: 'npm' })
        // Different versions are different components.
        expect(key('pkg:npm/lodash@4.17.20')).not.toBe(key('pkg:npm/lodash@4.17.21'))
        // An unparseable purl is its own identity rather than an error.
        expect(purlComponentIdentity('pkg:')).toEqual({ key: 'pkg:', label: 'pkg:' })
    })

    it('puts every finding of a component in one group with counts by severity, violations and KEV', () => {
        const groups = groupFindingsByComponent([
            row({ type: 'Vulnerability', id: 'DEBIAN-CVE-1', purl: GLIBC, severity: 'HIGH' }),
            row({ type: 'Vulnerability', id: 'DEBIAN-CVE-2', purl: 'pkg:deb/debian/glibc@2.36-9%2Bdeb12u14?distro=debian-12', severity: 'MEDIUM' }),
            row({ type: 'Vulnerability', id: 'DEBIAN-CVE-3', purl: GLIBC, severity: '-' }),
            row({ type: 'Violation', id: 'LICENSE', purl: GLIBC, severity: '-' }),
            row({ type: 'Vulnerability', id: 'CVE-2021-44228', purl: LOG4J, severity: 'CRITICAL', knownExploited: true })
        ])
        expect(groups.map(g => g.label)).toEqual(['org.apache.logging.log4j/log4j-core@2.14.1', 'debian/glibc@2.36-9+deb12u14'])
        const [log4j, glibc] = groups
        expect(log4j).toMatchObject({ ecosystem: 'maven', purl: LOG4J, kevCount: 1, violationCount: 0 })
        expect(glibc.rows).toHaveLength(4)
        expect(glibc.severityCounts).toEqual({ CRITICAL: 0, HIGH: 1, MEDIUM: 1, LOW: 0, UNASSIGNED: 1 })
        expect(glibc.violationCount).toBe(1)
        // The first row's purl is the dependency-graph target.
        expect(glibc.purl).toBe(GLIBC)
    })

    it('orders worst first: severity, then known-exploited, then size, then name', () => {
        const groups = groupFindingsByComponent([
            row({ type: 'Vulnerability', id: 'a', purl: 'pkg:npm/b@1', severity: 'HIGH' }),
            row({ type: 'Vulnerability', id: 'b', purl: 'pkg:npm/a@1', severity: 'HIGH' }),
            row({ type: 'Vulnerability', id: 'c', purl: 'pkg:npm/kev@1', severity: 'HIGH', knownExploited: true }),
            row({ type: 'Vulnerability', id: 'd', purl: 'pkg:npm/big@1', severity: 'HIGH' }),
            row({ type: 'Vulnerability', id: 'e', purl: 'pkg:npm/big@1', severity: 'LOW' }),
            row({ type: 'Violation', id: 'LICENSE', purl: 'pkg:npm/violation-only@1', severity: '-' }),
            row({ type: 'Vulnerability', id: 'f', purl: 'pkg:npm/crit@1', severity: 'CRITICAL' })
        ])
        expect(groups.map(g => g.label)).toEqual(['crit@1', 'kev@1', 'big@1', 'a@1', 'b@1', 'violation-only@1'])
    })

    it('groups weaknesses by their location and collects rows without one', () => {
        const groups = groupFindingsByComponent([
            row({ type: 'Weakness', id: 'CWE-79', purl: 'src/app/view.ts', severity: 'MEDIUM' }),
            row({ type: 'Weakness', id: 'CWE-89', purl: 'src/app/view.ts', severity: 'HIGH' }),
            row({ type: 'Violation', id: 'OPERATIONAL', purl: '', severity: '-' }),
            row({ type: 'Violation', id: 'SECURITY', purl: '-', severity: '-' })
        ])
        expect(groups.map(g => [g.label, g.ecosystem, g.purl, g.rows.length]))
            .toEqual([['src/app/view.ts', undefined, undefined, 2], ['No component', undefined, undefined, 2]])
    })

    it('counts the distinct components the findings touch, not rows without one', () => {
        expect(componentCountOf([
            row({ type: 'Vulnerability', id: 'a', purl: GLIBC }),
            row({ type: 'Vulnerability', id: 'b', purl: 'pkg:deb/debian/glibc@2.36-9%2Bdeb12u14' }),
            row({ type: 'Vulnerability', id: 'c', purl: LOG4J }),
            row({ type: 'Weakness', id: 'CWE-79', purl: 'src/app/view.ts' }),
            row({ type: 'Violation', id: 'OPERATIONAL', purl: '-' })
        ])).toBe(3)
        expect(componentCountOf([])).toBe(0)
    })
})

describe('findings filters', () => {
    it('buckets severities the way the Severity column filter does', () => {
        expect(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED', '-', '', undefined, 'INFO']
            .map(severity => severityBucketOf({ severity })))
            .toEqual(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED', 'UNASSIGNED', 'UNASSIGNED', 'UNASSIGNED', 'UNASSIGNED'])
    })

    it('treats an empty filter as no filter and combines Type and Severity', () => {
        const high = row({ type: 'Vulnerability', id: 'x', severity: 'HIGH' })
        const violation = row({ type: 'Violation', id: 'y', severity: '-' })
        expect(matchesFindingFilters(high, [], [])).toBe(true)
        expect(matchesFindingFilters(high, ['Vulnerability', 'Weakness'], ['HIGH'])).toBe(true)
        expect(matchesFindingFilters(high, ['Vulnerability'], ['CRITICAL'])).toBe(false)
        expect(matchesFindingFilters(violation, ['Vulnerability', 'Weakness'], [])).toBe(false)
        // As in the flat table, a violation counts as UNASSIGNED severity.
        expect(matchesFindingFilters(violation, [], ['UNASSIGNED'])).toBe(true)
    })
})

describe('opening filters', () => {
    it('seeds Type and Severity from the modal\'s initial props, copying arrays', () => {
        const types = ['Vulnerability', 'Weakness']
        const seeded = initialColumnFilters(types, 'HIGH')
        expect(seeded).toEqual({ type: ['Vulnerability', 'Weakness'], severity: ['HIGH'] })
        seeded.type.push('Violation')
        expect(types).toEqual(['Vulnerability', 'Weakness'])
        expect(initialColumnFilters('Violation', '')).toEqual({ type: ['Violation'], severity: [] })
        expect(initialColumnFilters('', undefined)).toEqual({ type: [], severity: [] })
    })
})

describe('grouped view preference', () => {
    afterEach(() => vi.unstubAllGlobals())

    it('remembers the choice and defaults to the flat view', () => {
        const store = new Map<string, string>()
        vi.stubGlobal('window', { localStorage: { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => store.set(k, v) } })
        expect(storedGroupByComponent()).toBe(false)
        storeGroupByComponent(true)
        expect(storedGroupByComponent()).toBe(true)
        storeGroupByComponent(false)
        expect(storedGroupByComponent()).toBe(false)
    })

    it('falls back to the flat view when storage is unavailable', () => {
        vi.stubGlobal('window', { localStorage: { getItem: () => { throw new Error('denied') }, setItem: () => { throw new Error('denied') } } })
        expect(storedGroupByComponent()).toBe(false)
        expect(() => storeGroupByComponent(true)).not.toThrow()
    })
})
