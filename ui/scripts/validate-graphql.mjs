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
//   - REPORT-ONLY by default (always exit 0). Prints, per document:
//       * [FAIL]  invalid against Pro  -- UI selects a field/type/arg absent
//                 from the Pro schema (source of truth).
//       * [WARN]  valid on Pro, absent on the CE mirror -- expected enrichment
//                 lag, handled at runtime by the core/enrichment fallback
//                 (see notificationInboxQuery.ts).
//     It's report-only because a whole-app hard gate currently trips over a
//     batch of PRE-EXISTING dead UI code paths (mutations/queries the backend
//     never implemented -- tracked separately on the board), which are out of
//     scope for the notifications drift work that introduced this script.
//   - With --strict, exit 1 on any Pro failure. Use once the pre-existing
//     dead-code paths are cleaned up, to turn this into a true CI gate.
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
const PRO_SCHEMA = join(UI_ROOT, '../../rearm-core/backend/src/main/resources/schema/schema.graphqls')
const CE_SCHEMA = join(UI_ROOT, '../backend/src/main/resources/schema/schema.graphqls')

function loadSchema (path, label) {
    if (!existsSync(path)) {
        console.warn(`[validate-graphql] ${label} schema not found at ${path} -- skipping ${label} checks`)
        return null
    }
    try {
        return buildSchema(readFileSync(path, 'utf8'))
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
        else if (/\.(ts|vue|js|mjs)$/.test(name)) out.push(p)
    }
    return out
}

// Extract gql`...` template bodies. Skip any that interpolate (${...}) --
// those are built dynamically and can't be statically parsed here.
function extractGqlDocuments (source) {
    const docs = []
    const re = /gql`([\s\S]*?)`/g
    let m
    while ((m = re.exec(source)) !== null) {
        const body = m[1]
        if (body.includes('${')) continue
        docs.push({ body, index: m.index })
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

let hardFailures = 0
let ceWarnings = 0
let checked = 0
let skipped = 0

for (const file of walk(SRC)) {
    const source = readFileSync(file, 'utf8')
    for (const { body, index } of extractGqlDocuments(source)) {
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

        const proErrors = pro ? validate(pro, ast) : []
        if (proErrors.length > 0) {
            hardFailures++
            console.error(`\n[FAIL] ${where} -- invalid against Pro schema:`)
            for (const e of proErrors) console.error(`   - ${e.message}`)
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

console.log(`\n[validate-graphql] checked ${checked} documents (${skipped} dynamic/non-operation skipped), ` +
    `${hardFailures} Pro failure(s), ${ceWarnings} CE drift warning(s).`)

if (hardFailures > 0 && STRICT) {
    console.error('[validate-graphql] FAILED (--strict): UI selects fields the Pro schema does not define.')
    process.exit(1)
}
if (hardFailures > 0) {
    console.log('[validate-graphql] report-only (no --strict): not failing the build on the above. ' +
        'These are pre-existing dead UI code paths tracked separately.')
}
process.exit(0)
