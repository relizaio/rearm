import { describe, it, expect, vi, afterEach } from 'vitest'
import {
    componentCountOf,
    componentIdentitiesOf,
    groupFindingsByComponent,
    initialColumnFilters,
    matchesFindingFilters,
    purlComponentIdentity,
    storedGroupByComponent,
    storeGroupByComponent
} from './findingGroups'
import { severityBucketOf } from './findingUtils'
import { FindingSbomMissReason } from '@/constants/findingSbomMissReason'
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

    it('carries the highest score and EPSS of its findings, null without scores', () => {
        const cvss = (n: number) => ({ type: 'CVSS_V3' as const, score: n, subScores: [] })
        const epss = (n: number) => ({ type: 'EPSS' as const, score: n, subScores: [] })
        const [group] = groupFindingsByComponent([
            row({ type: 'Vulnerability', id: 'a', purl: 'pkg:npm/a@1', severity: 'HIGH', topScore: cvss(7.5), epss: epss(0.02), scores: [] }),
            row({ type: 'Vulnerability', id: 'b', purl: 'pkg:npm/a@1', severity: 'HIGH', topScore: cvss(8.1), epss: epss(0.4), scores: [] }),
            row({ type: 'Vulnerability', id: 'c', purl: 'pkg:npm/a@1', severity: 'HIGH', topScore: null, epss: null, scores: [] })
        ])
        expect(group.worstScore?.score).toBe(8.1)
        expect(group.maxEpss?.score).toBe(0.4)
        const [unscored] = groupFindingsByComponent([row({ type: 'Vulnerability', id: 'd', purl: 'pkg:npm/d@1', severity: 'HIGH' })])
        expect(unscored.worstScore).toBeNull()
        expect(unscored.maxEpss).toBeNull()
    })

    it('breaks a severity tie on the worst score before known-exploited', () => {
        const cvss = (n: number) => ({ type: 'CVSS_V3' as const, score: n, subScores: [] })
        const groups = groupFindingsByComponent([
            row({ type: 'Vulnerability', id: 'a', purl: 'pkg:npm/kev@1', severity: 'HIGH', knownExploited: true, topScore: cvss(7.0) }),
            row({ type: 'Vulnerability', id: 'b', purl: 'pkg:npm/scored@1', severity: 'HIGH', topScore: cvss(8.8) }),
            row({ type: 'Vulnerability', id: 'c', purl: 'pkg:npm/unscored@1', severity: 'HIGH' }),
            row({ type: 'Vulnerability', id: 'd', purl: 'pkg:npm/crit@1', severity: 'CRITICAL', topScore: cvss(5.0) })
        ])
        expect(groups.map(g => g.label)).toEqual(['crit@1', 'scored@1', 'kev@1', 'unscored@1'])
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

describe('grouping on the server\'s SBOM match', () => {
    const TAR_12 = 'pkg:deb/debian/tar@1.34+dfsg-1.2+deb12u1?distro=debian-12'
    const TAR_12_15 = 'pkg:deb/debian/tar@1.34+dfsg-1.2+deb12u1?distro=debian-12.15'
    const matched = (component: string, canonicalPurl: string) =>
        ({ sbomComponentUuid: component, canonicalPurl, missReason: null })
    const missed = (missReason: FindingSbomMissReason) => ({ sbomComponentUuid: null, canonicalPurl: null, missReason })

    it('keys a matched finding by its component, so two distribution builds of one version stay apart', () => {
        const groups = groupFindingsByComponent([
            row({ type: 'Vulnerability', id: 'CVE-1', purl: TAR_12, severity: 'HIGH', sbomMatch: matched('c-12', TAR_12) }),
            row({ type: 'Vulnerability', id: 'CVE-2', purl: TAR_12_15, severity: 'LOW', sbomMatch: matched('c-12-15', TAR_12_15) }),
            // Dependency-Track's spelling of the same component
            row({ type: 'Vulnerability', id: 'CVE-3', purl: 'pkg:deb/debian/tar@1.34%2Bdfsg-1.2%2Bdeb12u1?distro=debian-12',
                severity: 'LOW', sbomMatch: matched('c-12', TAR_12) })
        ])
        expect(groups.map(g => [g.key, g.label, g.sbomComponentUuid, g.rows.map(r => r.id)])).toEqual([
            ['sbom:c-12', 'debian/tar@1.34+dfsg-1.2+deb12u1 (debian-12)', 'c-12', ['CVE-1', 'CVE-3']],
            ['sbom:c-12-15', 'debian/tar@1.34+dfsg-1.2+deb12u1 (debian-12.15)', 'c-12-15', ['CVE-2']]
        ])
        // the client grouping would have made one group of the three
        expect(componentCountOf(groups.flatMap(g => g.rows))).toBe(2)
    })

    it('puts a violation with the component its purl\'s findings matched, when they matched one', () => {
        const groups = groupFindingsByComponent([
            row({ type: 'Vulnerability', id: 'CVE-1', purl: LOG4J, severity: 'CRITICAL',
                sbomMatch: matched('c-log4j', 'pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1') }),
            row({ type: 'Violation', id: 'LICENSE', purl: LOG4J, severity: '-' })
        ])
        expect(groups).toHaveLength(1)
        expect(groups[0].key).toBe('sbom:c-log4j')
        expect(groups[0].violationCount).toBe(1)
        expect(groups[0].label).toBe('org.apache.logging.log4j/log4j-core@2.14.1')
    })

    it('groups an unmatched finding by its purl and flags a group the release\'s SBOM does not hold', () => {
        const carried = 'pkg:maven/org.apache.logging.log4j/log4j-core@2.24.3'
        const groups = groupFindingsByComponent([
            row({ type: 'Vulnerability', id: 'CVE-A', purl: carried, sbomMatch: missed(FindingSbomMissReason.NOT_IN_INVENTORY) }),
            row({ type: 'Vulnerability', id: 'CVE-B', purl: carried, sbomMatch: missed(FindingSbomMissReason.NOT_IN_INVENTORY) }),
            row({ type: 'Vulnerability', id: 'CVE-C', purl: GLIBC, sbomMatch: matched('c-glibc', GLIBC) })
        ])
        const byKey = new Map(groups.map(g => [g.key, g]))
        expect(byKey.get('pkg:maven/org.apache.logging.log4j/log4j-core@2.24.3')?.notInSbom).toBe(true)
        expect(byKey.get('pkg:maven/org.apache.logging.log4j/log4j-core@2.24.3')?.sbomComponentUuid).toBeUndefined()
        expect(byKey.get('sbom:c-glibc')?.notInSbom).toBe(false)
    })

    it('falls back to the purl identity for rows without a match (a backend that does not match)', () => {
        const groups = groupFindingsByComponent([
            row({ type: 'Vulnerability', id: 'CVE-1', purl: GLIBC }),
            row({ type: 'Vulnerability', id: 'CVE-2', purl: 'pkg:deb/debian/glibc@2.36-9%2Bdeb12u14?distro=debian-12' })
        ])
        expect(groups.map(g => [g.key, g.sbomComponentUuid, g.notInSbom])).toEqual([
            ['pkg:deb/debian/glibc@2.36-9+deb12u14', undefined, false]
        ])
    })

    it('keeps a finding the release\'s SBOM does not hold out of a matched group of the same package', () => {
        // a finding carried over from a debian-11 build, next to the debian-12 build the release ships
        const carriedOver = 'pkg:deb/debian/tar@1.34+dfsg-1.2+deb12u1?distro=debian-11'
        const groups = groupFindingsByComponent([
            row({ type: 'Vulnerability', id: 'CVE-1', purl: TAR_12, sbomMatch: matched('c-12', TAR_12) }),
            row({ type: 'Vulnerability', id: 'CVE-2', purl: carriedOver, sbomMatch: missed(FindingSbomMissReason.NOT_IN_INVENTORY) })
        ])
        expect(groups.map(g => [g.key, g.notInSbom, g.rows.map(r => r.id)]).sort()).toEqual([
            ['pkg:deb/debian/tar@1.34+dfsg-1.2+deb12u1', true, ['CVE-2']],
            ['sbom:c-12', false, ['CVE-1']]
        ])
    })

    it('never moves a row to another group when a filter hides others', () => {
        const vuln = row({ type: 'Vulnerability', id: 'CVE-1', purl: LOG4J, severity: 'CRITICAL',
            sbomMatch: matched('c-log4j', 'pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1') })
        const violation = row({ type: 'Violation', id: 'LICENSE', purl: LOG4J, severity: '-' })
        const all = [vuln, violation]
        const identities = componentIdentitiesOf(all)
        // Type filter = Violation: the vulnerability that ties the violation to its component is hidden
        const onlyViolations = groupFindingsByComponent([violation], identities)
        expect(onlyViolations.map(g => g.key)).toEqual(['sbom:c-log4j'])
        expect(componentCountOf(all, identities)).toBe(1)
    })
})
