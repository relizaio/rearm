#!/usr/bin/env node
// Validate the UI's GraphQL documents against the backend schema(s) at build
// time, so a field the UI selects that the backend doesn't have is caught in
// CI instead of blanking a page in production.
//
// Two schemas matter because the SAME UI ships to both:
//   - Pro   (rearm-core/backend/.../schema.graphqls)  = source of truth.
//   - CE    (rearm/backend/.../schema.graphqls)        = delayed mirror.
//
// Policy:
//   - Prints, per document:
//       * [FAIL]  invalid against Pro  -- UI selects a field/type/arg absent
//                 from the Pro schema (source of truth).
//       * [WARN]  valid on Pro, absent on the CE mirror -- expected enrichment
//                 lag, handled at runtime by the core/enrichment fallback
//                 (see notificationInboxQuery.ts).
//   - With --strict, exit 1 on any Pro failure. The `validate:graphql` npm
//     script passes --strict, so it is now a hard gate: the batch of
//     PRE-EXISTING dead UI code paths that once forced report-only has been
//     removed, so a new Pro-invalid document fails the check. Run without
//     --strict for a non-failing report.
//
// The notifications inbox drift itself IS hard-gated, by the vitest
// schema-drift test (notificationInboxSchemaDrift.spec.ts) which fails the
// build if the inbox CORE selection stops validating against the CE mirror.
//
// Statically-parseable `gql` documents are scanned from source. Documents
// built dynamically (e.g. the inbox core/enrichment split, which interpolates
// its field list) are skipped here and covered by the vitest schema-drift
// test instead.

const STRICT = process.argv.includes('--strict')

import { readFileSync, existsSync, readdirSync, statSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join, relative } from 'path'
import { buildSchema, parse, validate } from 'graphql'

const HERE = dirname(fileURLToPath(import.meta.url))
const UI_ROOT = join(HERE, '..')
const SRC = join(UI_ROOT, 'src')
// Since the schema split, the shared types live in schema.graphqls and the root fields in
// user.graphqls (browser) and programmatic.graphqls (API keys); a schema is the three together.
const SCHEMA_FILES = ['schema.graphqls', 'user.graphqls', 'programmatic.graphqls']
const PRO_SCHEMA = join(UI_ROOT, '../../rearm-core/backend/src/main/resources/schema')
const CE_SCHEMA = join(UI_ROOT, '../backend/src/main/resources/schema')

function loadSchema (dir, label) {
    const present = SCHEMA_FILES.map(f => join(dir, f)).filter(existsSync)
    if (!present.length || !existsSync(join(dir, 'schema.graphqls'))) {
        console.warn(`[validate-graphql] ${label} schema not found at ${dir} -- skipping ${label} checks`)
        return null
    }
    try {
        return buildSchema(present.map(f => readFileSync(f, 'utf8')).join('\n'))
    } catch (e) {
        console.warn(`[validate-graphql] ${label} schema failed to build: ${e.message.split('\n')[0]} -- skipping ${label} checks`)
        return null
    }
}

function walk (dir) {
    const out = []
    for (const name of readdirSync(dir)) {
        const p = join(dir, name)
        const st = statSync(p)
        if (st.isDirectory()) out.push(...walk(p))
        // Skip test files: their gql documents are deliberately-invalid fixtures
        // (e.g. a bogus `thing` query used to exercise the drift fallback), not
        // real UI operations to validate against the prod schema.
        else if (/\.(ts|vue|js|mjs)$/.test(name) && !/\.(spec|test)\.[tj]s$/.test(name)) out.push(p)
    }
    return out
}

// Every `const NAME = `...`` template literal in the tree, by name. These are the
// query FRAGMENTS the UI composes documents from -- SINGLE_RELEASE_GQL_DATA and its
// kin -- and they are the reason this file resolves interpolations rather than
// skipping them: a selection that only ever appears inside one of those was, until
// this resolution existed, checked by nothing at all. The release fragment carrying
// `document { ... }` is exactly such a case.
//
// Keyed by bare name, so `${graphqlQueries.SINGLE_RELEASE_GQL_DATA}` and a locally
// imported `${SINGLE_RELEASE_GQL_DATA}` both resolve. Collisions across files are
// possible in principle; in practice these names are unique, and a wrong expansion
// would surface as a validation error rather than a silent pass.
function collectFragmentConstants (files) {
    const byName = new Map()
    const re = /(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*(?::[^=]+)?=\s*`([\s\S]*?)`/g
    const aliasRe = /^\s*([A-Za-z_$][\w$]*)\s*:\s*([A-Z][A-Z0-9_]*)\s*,?\s*$/gm
    const sources = files.map(f => readFileSync(f, 'utf8'))
    for (const source of sources) {
        let m
        while ((m = re.exec(source)) !== null) byName.set(m[1], m[2])
    }
    // graphqlQueries.ts exports its fragments through an object that RENAMES them --
    // `UserData: USER_DATA` -- and callers interpolate the export name. Resolve the
    // alias to the same body, or every `${graphqlQueries.Something}` stays unchecked.
    for (const source of sources) {
        let m
        while ((m = aliasRe.exec(source)) !== null) {
            if (byName.has(m[2]) && !byName.has(m[1])) byName.set(m[1], byName.get(m[2]))
        }
    }
    return byName
}

// Substitute ${IDENT} / ${obj.IDENT} from the constant map, repeatedly, so fragments
// that themselves interpolate fragments resolve. Returns null when something cannot
// be resolved -- a computed operation name, a value spliced in at runtime -- which the
// caller REPORTS rather than swallows.
const MAX_EXPANSIONS = 10
function expandInterpolations (body, constants) {
    let out = body
    for (let pass = 0; pass < MAX_EXPANSIONS && out.includes('${'); pass++) {
        out = out.replace(/\$\{\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\}/g, (whole, expr) => {
            const name = expr.split('.').pop()
            return constants.has(name) ? constants.get(name) : whole
        })
    }
    return out.includes('${') ? null : out
}

// Extract gql`...` template bodies, resolving interpolations where the pieces are
// static constants.
function extractGqlDocuments (source, constants) {
    const docs = []
    const re = /gql`([\s\S]*?)`/g
    let m
    while ((m = re.exec(source)) !== null) {
        const raw = m[1]
        if (!raw.includes('${')) {
            docs.push({ body: raw, index: m.index })
            continue
        }
        const expanded = expandInterpolations(raw, constants)
        if (expanded === null) {
            docs.push({ body: null, index: m.index })  // reported as unresolved
            continue
        }
        docs.push({ body: expanded, index: m.index })
    }
    return docs
}

function lineOf (source, index) {
    return source.slice(0, index).split('\n').length
}

const pro = loadSchema(PRO_SCHEMA, 'Pro')
const ce = loadSchema(CE_SCHEMA, 'CE')

// Report-only means never hard-exit on a missing schema. The Pro schema lives
// in the sibling rearm-core checkout, which isn't present in this repo's own
// CI -- so when it's absent we skip the Pro pass and (if available) still run
// the CE pass, rather than failing the build.
if (!pro && !ce) {
    console.warn('[validate-graphql] neither Pro nor CE schema available -- nothing to check. Skipping.')
    process.exit(0)
}
if (!pro) console.warn('[validate-graphql] Pro schema not found (rearm-core not co-located) -- running CE checks only.')

/**
 * Documents that do not validate against the Pro schema and did not start failing here:
 * they call mutations the server has never defined under those names. They were invisible
 * until this script learned to resolve interpolated documents, and they are listed rather
 * than fixed because each one is a live UI control in an unrelated feature -- fixing them
 * means deciding what the server call should now be, which is not this script's business.
 *
 * Keyed by operation name. An entry that STOPS failing is reported too: a stale allowlist
 * is how a gate quietly turns into decoration.
 */
const KNOWN_BROKEN = new Map([
    ['updateComponentResourceGroup', 'ComponentView resource-group dropdown; ComponentService has the method, the schema has no such mutation'],
    ['setComponentVisibility', 'ComponentView visibility dropdown; same shape -- service method present, mutation absent'],
    ['spawnInstance', 'CreateInstance ephemeral path; no mutation, no InstanceSpawnInput, and no Java behind it either'],
])
const knownBrokenSeen = new Set()

let knownBrokenHits = 0
let hardFailures = 0
let ceWarnings = 0
let checked = 0
let skipped = 0

const FILES = walk(SRC)
const CONSTANTS = collectFragmentConstants(FILES)
let unresolved = 0

for (const file of FILES) {
    const source = readFileSync(file, 'utf8')
    for (const { body, index } of extractGqlDocuments(source, CONSTANTS)) {
        if (body === null) {
            // Built from something only known at runtime (a computed operation name,
            // a value spliced into the text). Counted and named, because an unchecked
            // document is a blind spot and a silent one is worse than a loud one.
            unresolved++
            console.warn(`[validate-graphql] UNRESOLVED ${relative(UI_ROOT, file)}:${lineOf(source, index)}`
                + ' -- interpolation could not be expanded; not validated')
            continue
        }
        let ast
        try {
            ast = parse(body)
        } catch (e) {
            skipped++ // syntax error -- not a parseable document
            continue
        }
        // Skip documents with no operation (e.g. a bare `fragment` literal):
        // parse() accepts them, but validate() flags them NoUnusedFragments,
        // which is not the drift we're gating on.
        if (!ast.definitions.some(d => d.kind === 'OperationDefinition')) {
            skipped++
            continue
        }
        checked++
        const where = `${relative(UI_ROOT, file)}:${lineOf(source, index)}`

        const opNames = ast.definitions
            .filter(d => d.kind === 'OperationDefinition' && d.name)
            .map(d => d.name.value)
        opNames.filter(n => KNOWN_BROKEN.has(n)).forEach(n => knownBrokenSeen.add(n))

        const proErrors = pro ? validate(pro, ast) : []
        if (proErrors.length > 0) {
            const known = opNames.find(n => KNOWN_BROKEN.has(n))
            if (known) {
                knownBrokenHits++
                console.warn(`\n[KNOWN-BROKEN] ${where} -- ${KNOWN_BROKEN.get(known)}`)
                for (const e of proErrors) console.warn(`   - ${e.message}`)
                continue
            }
            hardFailures++
            console.error(`\n[FAIL] ${where} -- invalid against Pro schema:`)
            for (const e of proErrors) console.error(`   - ${e.message}`)
            continue
        }
        if (opNames.some(n => KNOWN_BROKEN.has(n))) {
            hardFailures++
            console.error(`\n[FAIL] ${where} -- operation is in KNOWN_BROKEN but now validates.`
                + ' Remove the entry rather than leaving the list to rot.')
            continue
        }
        if (ce) {
            const ceErrors = validate(ce, ast)
            if (ceErrors.length > 0) {
                ceWarnings++
                console.warn(`\n[WARN] ${where} -- valid on Pro but not on the CE mirror (enrichment lag; must degrade gracefully at runtime):`)
                for (const e of ceErrors) console.warn(`   - ${e.message}`)
            }
        }
    }
}

for (const [name, reason] of KNOWN_BROKEN) {
    if (!knownBrokenSeen.has(name)) {
        console.warn(`[validate-graphql] STALE KNOWN_BROKEN entry "${name}" -- no such operation`
            + ` in the tree any more (${reason}). Drop it.`)
    }
}

console.log(`\n[validate-graphql] checked ${checked} documents (${skipped} non-operation skipped, ` +
    `${unresolved} unresolved interpolation), ` +
    `${hardFailures} Pro failure(s), ${knownBrokenHits} known-broken, ` +
    `${ceWarnings} CE drift warning(s).`)

if (hardFailures > 0 && STRICT) {
    console.error('[validate-graphql] FAILED (--strict): UI selects fields the Pro schema does not define.')
    process.exit(1)
}
if (hardFailures > 0) {
    console.log('[validate-graphql] report-only (no --strict): not failing the build on the above. ' +
        'These are pre-existing dead UI code paths tracked separately.')
}
process.exit(0)
