import { describe, it, expect } from 'vitest'
import { buildInstanceStatusRows, summarizeInstanceStatus, filterInstanceStatusRows } from './instanceStatusRollup'

const plan = (featureSet: string, ns: string, product: string, name: string, target: string, type = 'FOLLOW') => ({
    featureSet, namespace: ns, type, targetRelease: target ? `rel-${target}` : null,
    targetReleaseDetails: target ? { version: target } : null,
    featureSetDetails: { name: 'Base Feature Set', componentDetails: { uuid: product, name } }
})
const actual = (featureSet: string, ns: string, version: string | null, notMatchingSince: string | null = null) => ({
    featureSet, namespace: ns, matchedRelease: version ? `rel-${version}` : null,
    matchedReleaseDetails: version ? { version } : null, notMatchingSince
})

const instances = [
    {
        uuid: 'i1', uri: 'app.example.com', instanceType: 'STANDALONE_INSTANCE', environment: 'PRODUCTION',
        deploymentHealth: 'FAILING', deploymentFailures: [{ uuid: 'f1' }, { uuid: 'f2' }],
        releases: [{ isInError: true, namespace: 'hub' }, { isInError: false, namespace: 'cd' }],
        productPlans: [plan('fs1', 'hub', 'p-hub', 'Hub', '1.2.0'), plan('fs2', 'cd', 'p-cd', 'CD', '2.0.0'), plan('fs3', 'harbor', 'p-harbor', 'Harbor', '0.3.5')],
        productActuals: [actual('fs1', 'hub', '1.2.0'), actual('fs2', 'cd', '1.9.0', '2026-09-01T00:00:00Z'), actual('fs3', 'harbor', null)]
    },
    {
        uuid: 'i2', uri: 'bot.example.com', displayName: 'bot (server name)', instanceType: 'STANDALONE_INSTANCE', environment: 'TEST',
        deploymentHealth: 'HEALTHY', deploymentFailures: [], releases: [],
        productPlans: [plan('fs4', 'bot', 'p-bot', 'Bot', '22.04.22')],
        productActuals: [actual('fs4', 'bot', '22.04.22')]
    },
    {
        uuid: 'c1', name: 'the-cluster', instanceType: 'CLUSTER', instances: ['i3'], productPlans: [], productActuals: []
    },
    {
        uuid: 'i3', uri: 'child.example.com', instanceType: 'CLUSTER_INSTANCE', namespace: 'child', environment: 'TEST',
        productPlans: [plan('fs5', 'child', 'p-bot', 'Bot', '', 'INTEGRATE'), plan('fs6', 'child', 'p-old', 'Old', '', 'UNINSTALL')],
        productActuals: [actual('fs5', 'child', '22.04.13'), actual('fs6', 'child', null)]
    }
]

describe('buildInstanceStatusRows', () => {
    const rows = buildInstanceStatusRows(instances)

    it('yields one row per deployable instance and skips CLUSTER containers', () => {
        expect(rows.map(r => r.uuid).sort()).toEqual(['i1', 'i2', 'i3'])
    })

    it('rolls product plans up against actuals', () => {
        const r = rows.find(x => x.uuid === 'i1')!
        expect(r.products.map(p => [p.productName, p.sync, p.actualVersion, p.targetVersion])).toEqual([
            ['CD', 'DRIFT', '1.9.0', '2.0.0'],
            ['Harbor', 'NOT_MATCHED', '', '0.3.5'],
            ['Hub', 'MATCHED', '1.2.0', '1.2.0']
        ])
        expect([r.matched, r.drifted, r.notMatched]).toEqual([1, 1, 1])
        expect(r.sync).toBe('NOT_MATCHED')
        expect(r.namespaces).toEqual(['cd', 'harbor', 'hub'])
        expect(r.openFailures).toBe(2)
        expect(r.releasesInError).toBe(1)
        expect(r.health).toBe('FAILING')
    })

    it('treats UNINSTALL plans as not applicable and defaults health to HEALTHY', () => {
        const r = rows.find(x => x.uuid === 'i3')!
        expect(r.products.map(p => p.sync)).toEqual(['MATCHED', 'NOT_APPLICABLE'])
        expect(r.sync).toBe('MATCHED')
        expect(r.health).toBe('HEALTHY')
        expect(r.openFailures).toBe(0)
    })

    it('orders worst first', () => {
        expect(rows.map(r => r.uuid)).toEqual(['i1', 'i2', 'i3'])
    })

    it('prefers the server displayName and falls back to uri', () => {
        expect(rows.find(r => r.uuid === 'i2')!.displayName).toBe('bot (server name)')
        expect(rows.find(r => r.uuid === 'i1')!.displayName).toBe('app.example.com')
    })

    it('scopes to a perspective by product: keeps instances deploying at least one of its products', () => {
        const scoped = buildInstanceStatusRows(instances, new Set(['p-bot']))
        expect(scoped.map(r => r.uuid).sort()).toEqual(['i2', 'i3'])
        expect(scoped.find(r => r.uuid === 'i3')!.products.map(p => p.productName)).toEqual(['Bot'])
    })
})

describe('summarizeInstanceStatus / filterInstanceStatusRows', () => {
    const rows = buildInstanceStatusRows(instances)

    it('summarises the org', () => {
        expect(summarizeInstanceStatus(rows)).toEqual({
            instances: 3, matched: 2, drifted: 0, notMatched: 1, failing: 1, degraded: 0, openFailures: 2
        })
    })

    it('filters by environment, health, drift and text', () => {
        expect(filterInstanceStatusRows(rows, { environment: 'TEST' }).map(r => r.uuid)).toEqual(['i2', 'i3'])
        expect(filterInstanceStatusRows(rows, { health: 'FAILING' }).map(r => r.uuid)).toEqual(['i1'])
        expect(filterInstanceStatusRows(rows, { driftOnly: true }).map(r => r.uuid)).toEqual(['i1'])
        expect(filterInstanceStatusRows(rows, { text: 'harbor' }).map(r => r.uuid)).toEqual(['i1'])
        expect(filterInstanceStatusRows(rows, { text: 'Bot' }).map(r => r.uuid)).toEqual(['i2', 'i3'])
    })
})
