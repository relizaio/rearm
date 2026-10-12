// TEST-ONLY (reads files with fs): the SBOM score report fixtures, as the raw strings the
// backend returns. The six goldens are copied unchanged from rearm-cli
// internal/bomscore/testdata/golden/ at commit 9ead33a (branch 2026-10-sbom-score, merge 13f5368);
// two-profiles, skip-files and version-2 are written for these specs (design SCORE-6 4.1).
import { readFileSync } from 'fs'
import { join } from 'path'

export const GOLDENS = ['cisa-2026.cdx', 'cisa-2026.spdx', 'fda.cdx', 'fda.spdx', 'ntia-2021.cdx', 'ntia-2021.spdx']

// From the ui/ root vitest runs in: under happy-dom, import.meta.url is not a file URL.
const DIR = join(process.cwd(), 'src/utils/__fixtures__/sbomScore')

/** The fixture `<name>.json`, as text. */
export function fixtureText (name: string): string {
    return readFileSync(join(DIR, `${name}.json`), 'utf8')
}
