import { readFileSync, readdirSync, existsSync } from 'fs'
import { join } from 'path'
import { fileURLToPath } from 'url'
import { buildSchema, GraphQLEnumType, type GraphQLSchema } from 'graphql'

/**
 * TEST-ONLY (it reads the backend schema off disk with `fs`): the CE and Pro GraphQL schemas
 * the *SchemaDrift / *Coercion specs validate UI documents against. One loader for all of
 * them. Each spec used to carry its own copy, and eleven read `schema.graphqls` alone: when
 * the schema was split into several files all eleven broke together, at module load, so
 * every test in them stopped running and nothing said so.
 *
 * A schema is every `.graphql` / `.graphqls` file under the directory, joined -- the set the
 * backend loads (`schema/**` + `*.graphql*`) and serves on /graphql, the endpoint the UI talks
 * to. Globbing rather than naming the files is deliberate: the next split needs no change
 * here. scripts/validate-graphql.mjs reads the same set.
 *
 * CE ships in this repo, so it loads eagerly and a missing or unbuildable CE schema fails the
 * suite. Pro lives in the sibling rearm-core checkout: absent means null, and the Pro checks
 * gate on it with `it.runIf(proSchema)` so they report SKIPPED rather than a silent pass.
 */
const CE_SCHEMA_DIR = fileURLToPath(new URL(
    '../../../backend/src/main/resources/schema/', import.meta.url))
const PRO_SCHEMA_DIR = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/', import.meta.url))

/** The schema under `dir`, or null when it holds no schema files. Throws if they do not build. */
function loadSchemaDir (dir: string): GraphQLSchema | null {
    if (!existsSync(dir)) return null
    const files = readdirSync(dir, { recursive: true, encoding: 'utf8' })
        .filter(f => /\.graphqls?$/.test(f)).sort()
    return files.length
        ? buildSchema(files.map(f => readFileSync(join(dir, f), 'utf8')).join('\n'))
        : null
}

const ce = loadSchemaDir(CE_SCHEMA_DIR)
if (!ce) throw new Error(`CE mirror schema not found at ${CE_SCHEMA_DIR}`)
export const ceSchema: GraphQLSchema = ce
export const proSchema: GraphQLSchema | null = loadSchemaDir(PRO_SCHEMA_DIR)

/** The member names of enum `name` in `schema`, in declaration order. */
export function enumValuesOf (schema: GraphQLSchema, name: string): string[] {
    const type = schema.getType(name)
    if (!(type instanceof GraphQLEnumType)) throw new Error(`${name} is not an enum in this schema`)
    return type.getValues().map(v => v.name)
}
