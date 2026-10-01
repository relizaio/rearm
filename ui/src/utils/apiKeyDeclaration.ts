/**
 * API keys as declarative configuration (task RD3-11): an API_KEYS file knows a key by its declared name,
 * and an apply records where it came from. An org admin may declare a key made by hand under a name, so the
 * next apply naming it updates that key instead of creating another, or release the name. The rules mirror
 * the server's (ApiKeyDeclarativeService), so the page offers only what the server would take.
 */
import { tsFull } from '@/utils/agentTaskFormat'

/**
 * The key types a file declares: FREEFORM only. ORGANIZATION and ORGANIZATION_RW keys are deprecated and
 * component keys stay with their components; the server refuses a name on any other type.
 */
export const DECLARABLE_KEY_TYPES: readonly string[] = ['FREEFORM']

/** The longest declared name. */
export const MAX_DECLARED_NAME = 128

/** Whether an org admin is offered "Declare as…" (or a rename) on this key: a declarable type, live (not requested or denied). */
export function canDeclareKey (key: any, isAdmin: boolean): boolean {
    if (!isAdmin || !key) return false
    return DECLARABLE_KEY_TYPES.includes(key.type) && (key.status === 'ACTIVE' || key.status === 'INACTIVE')
}

/** Whether an org admin is offered "Release name": any key that carries one. */
export function canReleaseKeyName (key: any, isAdmin: boolean): boolean {
    return isAdmin && !!key?.declaredName
}

/** Why a name cannot be declared, or '' when it can. */
export function declaredNameProblem (name: string | null | undefined): string {
    if (!name || !name.trim()) return 'A key needs a name'
    if (name !== name.trim()) return 'A key\'s name has no leading or trailing spaces'
    if (name.length > MAX_DECLARED_NAME) return `A key's name is at most ${MAX_DECLARED_NAME} characters`
    if (Array.from(name).some(isControl)) return 'A key\'s name has no control characters'
    return ''
}

/** Java's Character.isISOControl, which the server checks. */
function isControl (ch: string): boolean {
    const c = ch.codePointAt(0) ?? 0
    return c <= 0x1f || (c >= 0x7f && c <= 0x9f)
}

/** Variables of declareApiKey that declare (or rename) the key: the name as typed, the server refuses padding. */
export function declarePayload (apiKeyUuid: string, name: string): { apiKeyUuid: string, name: string } {
    return { apiKeyUuid, name }
}

/** Variables of declareApiKey that release the key's name. */
export function releasePayload (apiKeyUuid: string): { apiKeyUuid: string, name: null } {
    return { apiKeyUuid, name: null }
}

/** "declared in <repo>/<path>" for a key an apply wrote; '' for one no apply touched. */
export function declaredSourceLabel (declarative: any): string {
    if (!declarative) return ''
    const repo = String(declarative.source?.repo ?? '').replace(/\/+$/, '')
    const path = String(declarative.source?.path ?? '').replace(/^\/+/, '')
    const where = [repo, path].filter(Boolean).join('/')
    return 'declared in ' + (where || 'a file')
}

/** The hover detail of {@link declaredSourceLabel}: the commit's short sha and when the apply ran. */
export function declaredSourceDetail (declarative: any): string {
    if (!declarative) return ''
    const parts: string[] = []
    const commit = declarative.source?.commit
    if (commit) parts.push('commit ' + String(commit).slice(0, 8))
    const at = tsFull(declarative.appliedAt)
    if (at) parts.push('applied ' + at)
    return parts.join(' · ')
}

/** The life of a secret minted through the declarative mint; '' when such secrets do not expire. */
export function secretExpiresLabel (days: number | null | undefined): string {
    if (days === null || days === undefined) return ''
    return `minted secrets live ${days} day${days === 1 ? '' : 's'}`
}
