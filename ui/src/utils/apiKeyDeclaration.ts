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

/** Why a name not in NFC form is refused: two spellings of one name would be two keys. */
export const NOT_NFC_NAME = 'A key\'s name must be in Unicode normalization form NFC (normalize it first)'

/** Why a name with a character that draws nothing, or stray whitespace, is refused; the code point and its place follow. */
export const INVISIBLE_NAME = 'A key\'s name has no control, invisible or bidirectional characters and no doubled or edge whitespace'

/**
 * Why a name cannot be declared, or '' when it can. `current` is the name the key carries now: as on the
 * server, the invisible-character check applies only to a name being assigned, so a key keeps a name it already has.
 */
export function declaredNameProblem (name: string | null | undefined, current?: string | null): string {
    if (!name || !name.trim()) return 'A key needs a name'
    if (name !== name.trim()) return 'A key\'s name has no leading or trailing spaces'
    if (name.length > MAX_DECLARED_NAME) return `A key's name is at most ${MAX_DECLARED_NAME} characters`
    if (Array.from(name).some(isControl)) return 'A key\'s name has no control characters'
    if (name === current) return ''
    return invisibleNameProblem(name)
}

/** Java's Character.isISOControl, which the server checks. */
function isControl (ch: string): boolean {
    const c = ch.codePointAt(0) ?? 0
    return c <= 0x1f || (c >= 0x7f && c <= 0x9f)
}

/*
 * The server's rule for a name being assigned, code point for code point. The tables are the server's; the
 * Unicode properties come from the browser, whose Unicode version can differ from the server's for code points
 * assigned lately, where the server's answer stands.
 */

/** Unicode 16 Default_Ignorable_Code_Point, as [first, last] ranges: code points that draw nothing. */
const DEFAULT_IGNORABLE: readonly (readonly [number, number])[] = [
    [0x00AD, 0x00AD], [0x034F, 0x034F], [0x061C, 0x061C], [0x115F, 0x1160], [0x17B4, 0x17B5], [0x180B, 0x180F],
    [0x200B, 0x200F], [0x202A, 0x202E], [0x2060, 0x206F], [0x3164, 0x3164], [0xFE00, 0xFE0F], [0xFEFF, 0xFEFF],
    [0xFFA0, 0xFFA0], [0xFFF0, 0xFFF8], [0x1BCA0, 0x1BCA3], [0x1D173, 0x1D17A], [0xE0000, 0xE0FFF]]

/** Blanks that are not default-ignorable but draw nothing all the same: the blank Braille pattern, the null notehead. */
const BLANKS: readonly number[] = [0x2800, 0x1D159]

/** Scripts whose shaping uses the zero-width joiner and non-joiner. */
const JOINING_SCRIPTS: readonly RegExp[] = ['Arabic', 'Syriac', 'Nko', 'Mongolian', 'Devanagari', 'Bengali', 'Gurmukhi',
    'Gujarati', 'Oriya', 'Tamil', 'Telugu', 'Kannada', 'Malayalam', 'Sinhala', 'Myanmar', 'Khmer', 'Tibetan', 'Lao']
    .map(s => new RegExp(`^\\p{Script=${s}}$`, 'u'))

const SPACE = 0x20
const ZWNJ = 0x200C
const ZWJ = 0x200D
/** Variation selector 15: show as text. */
const TEXT_PRESENTATION = 0xFE0E
/** Variation selector 16: show as emoji. */
const EMOJI_PRESENTATION = 0xFE0F
/** Combining enclosing keycap. */
const KEYCAP = 0x20E3
/** The waving black flag that a tag sequence turns into a subdivision flag. */
const BLACK_FLAG = 0x1F3F4
/** The cancel tag that ends a tag sequence. */
const CANCEL_TAG = 0xE007F

const PICTOGRAPHIC = /^\p{Extended_Pictographic}$/u
const EMOJI_STYLE = /^\p{Emoji_Presentation}$/u
const EMOJI_MODIFIER = /^\p{Emoji_Modifier}$/u
const INHERITED_SCRIPT = /^\p{Script=Inherited}$/u
/** Control, format, line and paragraph separators, surrogates, private use, unassigned, and space separators. */
const DRAWS_NOTHING = /^[\p{Cc}\p{Cf}\p{Zl}\p{Zp}\p{Cs}\p{Co}\p{Cn}\p{Zs}]$/u
const MARK = /^[\p{Mn}\p{Me}]$/u

function invisibleNameProblem (name: string): string {
    if (name.normalize('NFC') !== name) return NOT_NFC_NAME
    const cps = Array.from(name, ch => ch.codePointAt(0) ?? 0)
    for (let i = 0; i < cps.length; i++) {
        if (!drawsInName(cps, i)) {
            return `${INVISIBLE_NAME} (U+${cps[i].toString(16).toUpperCase().padStart(4, '0')} at code point ${i + 1})`
        }
    }
    return ''
}

/** Whether the code point at `i` is a visible part of the name. */
function drawsInName (cps: number[], i: number): boolean {
    const cp = cps[i]
    const before = i > 0 ? cps[i - 1] : -1
    const after = i + 1 < cps.length ? cps[i + 1] : -1
    if (cp === SPACE) return before >= 0 && after >= 0 && before !== SPACE
    if (cp === ZWJ && endsEmoji(before) && has(PICTOGRAPHIC, after)) return true
    if ((cp === ZWJ || cp === ZWNJ) && after >= 0) {
        const script = joiningScriptBefore(cps, i)
        if (script && has(script, after)) return true
    }
    // a presentation selector only where it changes something; VS16 also on a keycap base
    if (cp === EMOJI_PRESENTATION) {
        return (has(PICTOGRAPHIC, before) && !has(EMOJI_STYLE, before)) || (isKeycapBase(before) && after === KEYCAP)
    }
    if (cp === TEXT_PRESENTATION) return has(PICTOGRAPHIC, before) && has(EMOJI_STYLE, before)
    if (cp === KEYCAP) return before === EMOJI_PRESENTATION || isKeycapBase(before)
    if (isTag(cp)) return (before === BLACK_FLAG || isTag(before)) && (isTag(after) || after === CANCEL_TAG)
    if (cp === CANCEL_TAG) return isTag(before)
    if (isDefaultIgnorable(cp) || BLANKS.includes(cp)) return false
    if (has(DRAWS_NOTHING, cp)) return false
    if (has(MARK, cp)) return before >= 0 && before !== SPACE && !isDefaultIgnorable(before)
    return true
}

/** Whether the code point (-1 for none) has the property the pattern tests. */
function has (pattern: RegExp, cp: number): boolean {
    return cp >= 0 && pattern.test(String.fromCodePoint(cp))
}

/** Whether an emoji sequence can go on after this code point: a pictograph, its VS16 or its skin tone. */
function endsEmoji (cp: number): boolean {
    return has(PICTOGRAPHIC, cp) || cp === EMOJI_PRESENTATION || has(EMOJI_MODIFIER, cp)
}

/** The joining script of the text before `i`, past combining marks of no script of their own; null for none. */
function joiningScriptBefore (cps: number[], i: number): RegExp | null {
    for (let j = i - 1; j >= 0; j--) {
        if (has(INHERITED_SCRIPT, cps[j])) continue
        return JOINING_SCRIPTS.find(s => has(s, cps[j])) ?? null
    }
    return null
}

function isDefaultIgnorable (cp: number): boolean {
    return DEFAULT_IGNORABLE.some(([first, last]) => cp >= first && cp <= last)
}

function isKeycapBase (cp: number): boolean {
    return (cp >= 0x30 && cp <= 0x39) || cp === 0x23 || cp === 0x2A
}

/** A tag character of an emoji tag sequence (the cancel tag that ends one is CANCEL_TAG). */
function isTag (cp: number): boolean {
    return cp >= 0xE0020 && cp < CANCEL_TAG
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
