import { readFileSync, existsSync } from 'fs'
import { join } from 'path'
import { fileURLToPath } from 'url'
import { buildSchema, type GraphQLSchema } from 'graphql'

// Test-only (node): the backend schema as the schema-drift specs validate against it.
//
// Since the schema split a schema is three files, as scripts/validate-graphql.mjs assembles it:
// the shared types in schema.graphqls, the root fields in user.graphqls (browser) and
// programmatic.graphqls (API keys). The shared file uses types defined in a root file --
// MatchOperator lives in programmatic.graphqls -- so schema.graphqls does not build on its own,
// and a spec that loaded it alone failed with 'Unknown type "MatchOperator"'.
export const SCHEMA_FILES = ['schema.graphqls', 'user.graphqls', 'programmatic.graphqls']

/** The CE mirror, in this repository. */
export const CE_SCHEMA_DIR = fileURLToPath(new URL('../../../backend/src/main/resources/schema', import.meta.url))

/** Pro, from a sibling rearm-core checkout; absent in this repository's own CI. */
export const PRO_SCHEMA_DIR = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema', import.meta.url))

/** The schema in {@code dir}, or null when the directory has no schema.graphqls. */
export function loadSchemaDir (dir: string): GraphQLSchema | null {
    if (!existsSync(join(dir, 'schema.graphqls'))) return null
    return buildSchema(SCHEMA_FILES.map(f => join(dir, f)).filter(existsSync)
        .map(f => readFileSync(f, 'utf8')).join('\n'))
}
