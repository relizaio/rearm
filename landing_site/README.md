# ReARM landing website (rearmhq.com)

Astro 5 static site, Origami Facets design (2026 rebuild).

- `npm ci && npm run dev` — local dev server
- `npm run build` — static build to `dist/`
- Blog/news posts are markdown in `src/content/{posts,news}`; the filename
  (minus `.md`) is the permalink slug. Markdown renders with gfm/smartypants
  disabled for parity with the previous site's pipeline.
- Per-page copy for product/use-case pages lives in `src/data/design.json`.
- Docker: multi-stage build (node -> nginx), configs in `nginx/`.
- `deploy/k8s.yaml` — sandbox deployment manifests (namespace landing-2026).
