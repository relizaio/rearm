// The two views the UI can show: Security (the classic page) and DevOps.
// Kept as a tiny module so the store, the header dropdown, the dashboard and
// the component page all agree on the values and on the wire form.

export const DASHBOARD_VIEWS = ['security', 'devops'] as const
export type DashboardView = typeof DASHBOARD_VIEWS[number]

export function isDashboardView (v: unknown): v is DashboardView {
    return DASHBOARD_VIEWS.includes(v as DashboardView)
}

export const DASHBOARD_VIEW_LABELS: Record<DashboardView, string> = {
    security: 'Security',
    devops: 'DevOps'
}

// GraphQL enum DashboardView on org settings uses upper-case names.
export function dashboardViewToWire (v: DashboardView): string {
    return v.toUpperCase()
}
export function dashboardViewFromWire (v: unknown): DashboardView | null {
    if (typeof v !== 'string') return null
    const lower = v.toLowerCase()
    return isDashboardView(lower) ? lower : null
}
