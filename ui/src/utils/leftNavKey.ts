// Which side-nav entry a route lights (task RD2-19, sweep UI-40). A route that belongs to an entry says so in
// its meta (meta.nav: the entry's key) -- every AI Agents page does, the task and session pages included, which
// the name map below missed. Other routes keep the name map. Pure, so the rule is testable without the nav.

/** Route names to the side-nav entry they belong to, for routes that carry no meta.nav. */
export const ROUTE_TO_MENU_KEY: Record<string, string> = {
    'home': 'home',
    'ComponentsOfOrg': 'components',
    'ProductsOfOrg': 'products',
    'VcsReposOfOrg': 'vcsRepos',
    'VcsRepository': 'vcsRepos',
    'PullRequestsOfOrg': 'pullRequests',
    'PullRequestView': 'pullRequests',
    'CommittersOfOrg': 'orgsettings',
    'CommitterView': 'orgsettings',
    'InstancesOfOrg': 'instances',
    'Instance': 'instances',
    'DistributionOfOrg': 'distribution',
    'SecretsOfOrg': 'secrets',
    'AnalyticsOfOrg': 'analytics',
    'VulnerabilityAnalysis': 'vulnerabilityAnalysis',
    'VexProposalReview': 'vulnerabilityAnalysis',
    'MitigationAttestationReview': 'vulnerabilityAnalysis',
    'OrgSettings': 'orgsettings'
}

/** The entry a route lights: its meta.nav, else its name's, else none (the nav keeps what it shows). */
export function navKeyOf (name: unknown, meta: Record<string, unknown> | null | undefined): string | null {
    const fromMeta = meta?.nav
    if (typeof fromMeta === 'string' && fromMeta) return fromMeta
    return typeof name === 'string' ? ROUTE_TO_MENU_KEY[name] ?? null : null
}
