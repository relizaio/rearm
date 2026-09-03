import constants from '@/utils/constants'

// Pure roll-up behind the DevOps dashboard's "Instance status" widget: one row
// per instance, summarising its product plans against what is actually
// deployed. Kept free of Vue / store so it is unit-testable and so the widget
// stays a thin renderer.

export type ProductSyncState = 'MATCHED' | 'DRIFT' | 'NOT_MATCHED' | 'NOT_APPLICABLE'
export type InstanceSyncState = 'MATCHED' | 'DRIFT' | 'NOT_MATCHED' | 'EMPTY'
export type DeploymentHealth = 'HEALTHY' | 'DEGRADED' | 'FAILING'

export interface ProductStatusRow {
    productUuid: string
    productName: string
    featureSetUuid: string
    featureSetName: string
    namespace: string
    planType: string
    targetVersion: string
    actualVersion: string
    sync: ProductSyncState
    notMatchingSince: string | null
}

export interface InstanceStatusRow {
    uuid: string
    uri: string
    displayName: string
    instanceType: string
    environment: string
    namespaces: string[]
    products: ProductStatusRow[]
    matched: number
    drifted: number
    notMatched: number
    sync: InstanceSyncState
    health: DeploymentHealth
    openFailures: number
    releasesInError: number
}

export interface InstanceStatusSummary {
    instances: number
    matched: number
    drifted: number
    notMatched: number
    failing: number
    degraded: number
    openFailures: number
}

export interface InstanceStatusFilters {
    environment?: string
    health?: DeploymentHealth | ''
    driftOnly?: boolean
    text?: string
}

const CLUSTER = constants.InstanceType.CLUSTER
// Plan types the roll-up treats specially: UNINSTALL is being removed (its
// actual state is not a sync signal); INTEGRATE has no target release.
export const PLAN_TYPE_UNINSTALL = 'UNINSTALL'
export const PLAN_TYPE_INTEGRATE = 'INTEGRATE'

function planKey (featureSet: string, namespace: string): string {
    return `${featureSet}|${namespace || ''}`
}

function productSync (plan: any, actual: any): ProductSyncState {
    if (plan.type === PLAN_TYPE_UNINSTALL) return 'NOT_APPLICABLE'
    if (!actual || !actual.matchedRelease) return 'NOT_MATCHED'
    return actual.notMatchingSince ? 'DRIFT' : 'MATCHED'
}

function instanceSync (products: ProductStatusRow[]): InstanceSyncState {
    const counted = products.filter(p => p.sync !== 'NOT_APPLICABLE')
    if (!counted.length) return 'EMPTY'
    if (counted.some(p => p.sync === 'NOT_MATCHED')) return 'NOT_MATCHED'
    if (counted.some(p => p.sync === 'DRIFT')) return 'DRIFT'
    return 'MATCHED'
}

// The backend's displayName is documented as non-empty for any persisted
// instance; the fallbacks only matter for hand-built fixtures.
function displayNameOf (inst: any): string {
    return inst.displayName || inst.uri || inst.name || inst.uuid
}

/**
 * Build one row per deployable instance. CLUSTER containers are skipped --
 * their child instances are the rows that deploy things and are listed in
 * the same org query. When `perspectiveProducts` is given, only instances
 * that deploy at least one of those products are kept, and their product
 * list is narrowed to those products.
 */
export function buildInstanceStatusRows (instances: any[], perspectiveProducts?: Set<string> | null): InstanceStatusRow[] {
    const rows: InstanceStatusRow[] = []
    ;(instances || []).forEach((inst: any) => {
        if (!inst || inst.instanceType === CLUSTER) return
        const actualsByKey: Record<string, any> = {}
        ;(inst.productActuals || []).forEach((a: any) => { actualsByKey[planKey(a.featureSet, a.namespace)] = a })
        const products: ProductStatusRow[] = []
        ;(inst.productPlans || []).forEach((plan: any) => {
            const comp = plan.featureSetDetails && plan.featureSetDetails.componentDetails
            const productUuid = (comp && comp.uuid) || ''
            if (perspectiveProducts && !perspectiveProducts.has(productUuid)) return
            const actual = actualsByKey[planKey(plan.featureSet, plan.namespace)]
            products.push({
                productUuid,
                productName: (comp && comp.name) || productUuid,
                featureSetUuid: plan.featureSet,
                featureSetName: (plan.featureSetDetails && plan.featureSetDetails.name) || '',
                namespace: plan.namespace || '',
                planType: plan.type || '',
                targetVersion: (plan.targetReleaseDetails && plan.targetReleaseDetails.version) || '',
                actualVersion: (actual && actual.matchedReleaseDetails && actual.matchedReleaseDetails.version) || '',
                sync: productSync(plan, actual),
                notMatchingSince: (actual && actual.notMatchingSince) || null
            })
        })
        if (perspectiveProducts && !products.length) return
        products.sort((a, b) => a.productName.localeCompare(b.productName) || a.namespace.localeCompare(b.namespace))
        const namespaces = new Set<string>()
        products.forEach(p => { if (p.namespace) namespaces.add(p.namespace) })
        ;(inst.releases || []).forEach((r: any) => { if (r && r.namespace) namespaces.add(r.namespace) })
        const health: DeploymentHealth = inst.deploymentHealth || 'HEALTHY'
        rows.push({
            uuid: inst.uuid,
            uri: inst.uri || '',
            displayName: displayNameOf(inst),
            instanceType: inst.instanceType || '',
            environment: inst.environment || '',
            namespaces: Array.from(namespaces).sort(),
            products,
            matched: products.filter(p => p.sync === 'MATCHED').length,
            drifted: products.filter(p => p.sync === 'DRIFT').length,
            notMatched: products.filter(p => p.sync === 'NOT_MATCHED').length,
            sync: instanceSync(products),
            health,
            openFailures: (inst.deploymentFailures || []).length,
            releasesInError: (inst.releases || []).filter((r: any) => r && r.isInError).length
        })
    })
    // Worst first: failing / not matched at the top, then by name.
    const healthRank: Record<DeploymentHealth, number> = { FAILING: 0, DEGRADED: 1, HEALTHY: 2 }
    const syncRank: Record<InstanceSyncState, number> = { NOT_MATCHED: 0, DRIFT: 1, MATCHED: 2, EMPTY: 3 }
    rows.sort((a, b) => healthRank[a.health] - healthRank[b.health]
        || syncRank[a.sync] - syncRank[b.sync]
        || a.displayName.localeCompare(b.displayName))
    return rows
}

export function summarizeInstanceStatus (rows: InstanceStatusRow[]): InstanceStatusSummary {
    return {
        instances: rows.length,
        matched: rows.filter(r => r.sync === 'MATCHED').length,
        drifted: rows.filter(r => r.sync === 'DRIFT').length,
        notMatched: rows.filter(r => r.sync === 'NOT_MATCHED').length,
        failing: rows.filter(r => r.health === 'FAILING').length,
        degraded: rows.filter(r => r.health === 'DEGRADED').length,
        openFailures: rows.reduce((n, r) => n + r.openFailures, 0)
    }
}

export function filterInstanceStatusRows (rows: InstanceStatusRow[], filters: InstanceStatusFilters): InstanceStatusRow[] {
    const text = (filters.text || '').trim().toLowerCase()
    return rows.filter(r => {
        if (filters.environment && r.environment !== filters.environment) return false
        if (filters.health && r.health !== filters.health) return false
        if (filters.driftOnly && r.sync !== 'DRIFT' && r.sync !== 'NOT_MATCHED') return false
        if (text) {
            const hay = [r.displayName, r.uri, r.environment, ...r.namespaces, ...r.products.map(p => p.productName)].join(' ').toLowerCase()
            if (!hay.includes(text)) return false
        }
        return true
    })
}
