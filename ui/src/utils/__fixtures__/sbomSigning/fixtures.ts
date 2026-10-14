// TEST-ONLY (reads files with fs): signed and unsigned CycloneDX JSON exports for the signing
// status specs (SCORE-35). signed-ed25519, unsigned, malformed and keyid-only are copied
// unchanged from rearm-cli internal/bomscore/testdata/jsf/ at commit ad946c6 (SCORE-34, branch
// 2026-10-sbom-score); signing-error, unstamped, duplicate-stamp and not-json.txt are written
// for these specs (design SCORE-35 section 4). forged-nokey is a real rearm-saas export (SCORE-33
// tester run 1, docs tests/SCORE-33/run-1-probe-out.tar.gz) from an organization without a key:
// near-name stamps from an upload survive the egress sweep ahead of the real NO_KEY stamp.
import { readFileSync } from 'fs'
import { join } from 'path'

// From the ui/ root vitest runs in: under happy-dom, import.meta.url is not a file URL.
const DIR = join(process.cwd(), 'src/utils/__fixtures__/sbomSigning')

/** The fixture file `<name>` (with its extension), as text. */
export function signingFixture (name: string): string {
    return readFileSync(join(DIR, name), 'utf8')
}
