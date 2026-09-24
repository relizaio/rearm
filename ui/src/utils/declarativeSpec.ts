// Reading a declarative spec file in the browser, for the "Apply spec" upload.
//
// The file goes to the server as the object it parsed to, nulls and all: a field set to null clears
// it and a field left out leaves it alone (declarative-boards D15), so nothing here may drop a null
// or fill in a default. Pure, so the parsing rules are testable without mounting anything.

import { parse } from 'yaml'

export type SpecKind = 'BOARD' | 'ROLE_PRESETS'

export interface ParsedSpec {
    kind: SpecKind
    spec: Record<string, any>
}

/**
 * Parse a YAML or JSON spec file (JSON is YAML) and check it is one of the kinds this upload takes.
 * Throws with a message fit to show when it is not.
 */
export function parseSpecFile (text: string, accepted: SpecKind[]): ParsedSpec {
    let doc: any
    try {
        doc = parse(text)
    } catch (e: any) {
        throw new Error(`Not valid YAML or JSON: ${e?.message ?? e}`)
    }
    if (!doc || typeof doc !== 'object' || Array.isArray(doc)) {
        throw new Error('The file is not a spec: it should be a mapping with a kind')
    }
    const kind = doc.kind
    if (!accepted.includes(kind)) {
        throw new Error(kind
            ? `This upload takes ${accepted.join(' or ')} files, not ${kind}`
            : `The file has no kind; expected ${accepted.join(' or ')}`)
    }
    if (doc.version === undefined) doc.version = 1
    return { kind, spec: doc }
}

/**
 * The references a file carries that only the CLI and the Terraform provider can resolve -- a role
 * or preset `file:` or `promptFile:`, a board's `coordinatorPromptFile:` -- named so the refusal says
 * which (D3). A browser upload cannot read the files next to the one picked.
 */
export function specReferences (parsed: ParsedSpec): string[] {
    const out: string[] = []
    const s = parsed.spec
    if (s.coordinatorPromptFile !== undefined) out.push(`coordinatorPromptFile: ${s.coordinatorPromptFile}`)
    const listKey = parsed.kind === 'BOARD' ? 'roles' : 'presets'
    const entries: any[] = Array.isArray(s[listKey]) ? s[listKey] : []
    entries.forEach((e, i) => {
        if (!e || typeof e !== 'object') return
        for (const k of ['file', 'promptFile']) {
            if (e[k] !== undefined) out.push(`${listKey}[${i}].${k}: ${e[k]}`)
        }
    })
    return out
}

/** What a change entry is about, for the change-set table. */
export function changeEntity (kind: string, action: string, message?: string | null): string {
    if (kind === 'ROLE_PRESETS') return 'preset'
    if (kind === 'BOARD') {
        // A board file's only archives are roles it no longer lists.
        return message === 'role' || action === 'ARCHIVE' ? 'role' : 'board'
    }
    return String(kind ?? '').toLowerCase()
}

/** A change's message without the entity marker the server puts on board-file changes. */
export function changeMessage (kind: string, message?: string | null): string {
    if (kind === 'BOARD' && (message === 'role' || message === 'board')) return ''
    return message ?? ''
}
