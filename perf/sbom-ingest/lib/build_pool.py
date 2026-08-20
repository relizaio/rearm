#!/usr/bin/env python3
"""
Build a component pool for the SBOM-ingest load test.

The load test does NOT ship a corpus of pre-generated SBOMs. At 4.9 KB per
component (a real CycloneDX component carries licenses, hashes and properties),
20,000 x 50-component SBOMs would be 5 GB on disk for a single run. Instead we
extract the components ONCE into a pool, and the JMeter plan synthesizes each
release's BOM in memory at request time. Disk cost of the corpus is therefore
the pool alone (a few MB), not the run.

Input is one or more real CycloneDX documents. Real components matter: purls
that resolve to nothing make the downstream vulnerability analysis a no-op, and
a load test that skips the expensive part measures nothing.

Usage:
    build_pool.py --out pool.json <source.cdx.json> [<source2.cdx.json> ...]
"""

import argparse
import collections
import json
import sys

# Fields carried into the pool by default. This is a LEAN component: roughly
# 0.5 KB, against ~4.9 KB for the same component in the source product BOM.
# The difference is evidence/properties/externalReferences, which are bulk that
# the ingest path still has to parse. Lean BOMs therefore flatter the numbers on
# parse cost -- use --rich to keep that weight and measure the realistic case.
# Whichever you pick, state it in the run record; it is not a free choice.
KEEP = ("type", "name", "version", "group", "purl", "licenses", "hashes",
        "description", "supplier", "publisher", "cpe", "scope")

RICH_EXTRA = ("properties", "externalReferences", "evidence")


def extract(doc):
    """Yield components from a CycloneDX doc, flattening nested components.

    Tolerates non-CycloneDX JSON in the source set (some files alongside real
    SBOMs are bare arrays or unrelated payloads) by yielding nothing.
    """
    if not isinstance(doc, dict):
        return

    def walk(comps):
        for c in comps:
            if not isinstance(c, dict):
                continue
            yield c
            if c.get("components"):
                yield from walk(c["components"])
    yield from walk(doc.get("components") or [])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sources", nargs="+")
    ap.add_argument("--out", required=True)
    ap.add_argument("--max-desc", type=int, default=200,
                    help="truncate description to N chars (0 disables)")
    ap.add_argument("--rich", action="store_true",
                    help="also keep properties/externalReferences/evidence, "
                         "reproducing real-world component weight (~10x larger)")
    args = ap.parse_args()

    keep = KEEP + RICH_EXTRA if args.rich else KEEP
    pool = {}
    eco = collections.Counter()

    for src in args.sources:
        try:
            doc = json.load(open(src, encoding="utf-8"))
        except Exception as e:
            print(f"skip {src}: {e}", file=sys.stderr)
            continue
        for c in extract(doc):
            purl = c.get("purl")
            if not purl or purl in pool:
                continue
            entry = {k: c[k] for k in keep if k in c}
            if args.max_desc and "description" in entry:
                entry["description"] = entry["description"][: args.max_desc]
            pool[purl] = entry
            eco[purl.split(":")[1].split("/")[0] if ":" in purl else "?"] += 1

    comps = list(pool.values())
    if not comps:
        sys.exit("no components with a purl found in the given sources")

    out = {
        "poolVersion": 1,
        "profile": "rich" if args.rich else "lean",
        "componentCount": len(comps),
        "ecosystems": dict(eco.most_common()),
        "components": comps,
    }
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(out, fh, separators=(",", ":"))

    size = len(json.dumps(out, separators=(",", ":")))
    print(f"pool written: {args.out}")
    print(f"  components : {len(comps)}")
    print(f"  size       : {size / 1024 / 1024:.2f} MB "
          f"({size / len(comps) / 1024:.2f} KB per component)")
    print(f"  ecosystems : {dict(eco.most_common(12))}")


if __name__ == "__main__":
    main()
