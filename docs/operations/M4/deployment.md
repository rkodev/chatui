# Web deployment

This guide deploys the ChatUI web client as a static site. The app is a
client-only SPA: it has no server-side prompt proxy, no analytics, and no
required environment variables. Provider keys stay in the browser and are never
part of the image or `config.json`.

The reference deployment is a minimal Caddy container. The same header and
cache rules are provided for the supported static hosts.

## Artifacts

| Path | Purpose |
| --- | --- |
| `deploy/Dockerfile` | Multi-stage build: Node builds `web/dist`, Caddy serves it |
| `deploy/Caddyfile` | SPA fallback, cache rules, security headers, `/healthz` |
| `deploy/docker-compose.yml` | One-command local or single-host deployment |
| `deploy/cloudflare-pages/_headers` | Cloudflare Pages header rules |
| `deploy/netlify.toml` | Netlify build, redirect, and header rules |
| `deploy/vercel.json` | Vercel build, rewrite, and header rules |
| `deploy/github-pages/README.md` | GitHub Pages fallback recipe and limitations |
| `deploy/roksky.md` | roksky tenant recipe |
| `docs/operations/M4/evidence/deployment/README.md` | Recorded smoke evidence |

## Prerequisites

- Docker 24 or newer with BuildKit.
- Node 22.12 or newer and pnpm 11.7.0 for local builds outside Docker.
- No application environment variables. `config.json` is the only runtime
  configuration and contains public values only.

## Container

Build from the repository root so the build stage can read
`contracts/providers.json`:

```bash
make web-build
docker build -f deploy/Dockerfile -t chatui-web:local .
docker run --rm -p 8080:80 chatui-web:local
```

Open `http://localhost:8080/`. The image exposes port `80` and runs Caddy as a
non-root-capable static server. The build pins these base images:

- `node:22-alpine@sha256:0e1754758c2df4cfa3e68d59e2e1b7374bb7fd9ea6328eaaacf15cc934af8202`
- `caddy:2-alpine@sha256:78bd35025c7ba85961dd149ae670315129954e77abe2383ac47c89106b35ade9`

The final image contains only `/srv` (the built static files) and the Caddy
configuration. It does not contain `node_modules`, source, tests, or secrets.

### Docker Compose

```bash
docker compose -f deploy/docker-compose.yml up --build
```

The service publishes `8080:80` and uses `GET /healthz` as its healthcheck.
Stop it with `docker compose -f deploy/docker-compose.yml down`.

## Header and cache contract

Every target must reproduce these rules. The container implements them in
`deploy/Caddyfile`; the platform recipes implement the same values.

| Path | `Cache-Control` | Notes |
| --- | --- | --- |
| `/`, `/index.html`, deep routes | `no-cache` | SPA shell; redeploys are picked up immediately |
| `/config.json` | `no-cache` | Runtime config; the app also fetches with `cache: 'no-store'` |
| `/sw.js`, `/manifest.webmanifest` | `no-cache` | Service worker and install metadata |
| `/assets/*` | `public, max-age=31536000, immutable` | Vite content-hashed output |

Security headers on every response:

```text
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Permissions-Policy: camera=(), microphone=(), geolocation=()
Cross-Origin-Opener-Policy: same-origin
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' https: http://localhost:* http://127.0.0.1:*; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'
```

The CSP is the spec 8.6/14.1 policy. `style-src 'unsafe-inline'` is required by
the existing styling approach and is the only inline allowance; `script-src`
stays `'self'`. `connect-src` allows HTTPS provider origins plus localhost for
local model servers. Do not add third-party script origins.

`/healthz` returns a static `200` with an empty body. It does not touch the
filesystem, providers, or configuration.

## Runtime configuration

`web/public/config.json` is copied into the build output and served at
`/config.json`. It contains only public fields:

- `appName`, `defaultTheme`, `defaultProviderPreset`
- `enabledPresets`, `allowCustomProviders`
- `openRouter.attributionOrigin`, `openRouter.appTitle`
- `docsUrl`, `telemetry`

The app validates it at startup in `web/src/app/config.ts` with a strict Zod
schema. Unknown fields, malformed JSON, missing fields, unknown presets, and
invalid preset combinations fail closed with a visible configuration error.
Never add keys, tokens, or private endpoints to this file.

To change branding or enabled presets, edit `web/public/config.json`, rebuild,
and redeploy. No environment variables are involved.

## Platform recipes

### Cloudflare Pages

1. Connect the repository and set the build command to
   `pnpm --dir web build` and the output directory to `web/dist`.
2. Copy `deploy/cloudflare-pages/_headers` to `web/dist/_headers` after the
   build, or paste the same rules into the Pages dashboard.
3. Cloudflare Pages serves `index.html` for unknown routes when the SPA
   fallback setting is enabled. Confirm a deep route returns the app shell.

### Netlify

1. Copy `deploy/netlify.toml` to the repository root, or merge its sections into
   an existing `netlify.toml`.
2. Connect the repository. The recipe builds `web/` with `pnpm build` and
   publishes `web/dist`.
3. The redirect rule provides SPA fallback; the header rules provide the cache
   and security contract.

### Vercel

1. Copy `deploy/vercel.json` to the repository root.
2. Import the repository. The recipe installs and builds `web/`, publishes
   `web/dist`, rewrites unknown paths to `index.html`, and applies the headers.
3. Confirm the project uses the repository root, not `web/`, so the paths in
   `vercel.json` resolve.

### GitHub Pages

Follow `deploy/github-pages/README.md`. GitHub Pages cannot set response
headers, so it does not meet the CSP/cache contract. Use it for previews or
low-risk deployments only. Owner for the header gap: web deployment owner.

### roksky hosting

Follow `deploy/roksky.md`. roksky serves the same static bundle on a tenant
subdomain or custom domain, manages TLS, and applies the shared header rules at
the edge. Optional managed storage is for encrypted backups only; roksky support
never receives provider keys, plaintext chats, or backup passphrases.

## Verification

Run the container and check each asset class:

```bash
make web-build
docker build -f deploy/Dockerfile -t chatui-web:local .
docker run --rm -d --name chatui-web-smoke -p 8080:80 chatui-web:local
curl -sSI http://localhost:8080/
curl -sSI http://localhost:8080/config.json
curl -sSI http://localhost:8080/healthz
curl -sS http://localhost:8080/some/deep/route | head
docker stop chatui-web-smoke
```

Expected results:

- `/` and `/config.json` return `Cache-Control: no-cache`.
- `/healthz` returns `200` with an empty body.
- `/some/deep/route` returns `200` and the `index.html` shell.
- A file under `/assets/` returns
  `Cache-Control: public, max-age=31536000, immutable`.
- Every response includes the security headers above.

If `curl` is unavailable in your environment, use any HTTP client and inspect
the same response headers. The recorded run in
`docs/operations/M4/evidence/deployment/README.md` used Node's `fetch` because
the execution environment denied `curl`.

## Troubleshooting

- **Deep route returns 404:** confirm the SPA fallback is active. In Caddy the
  `try_files {path} /index.html` line must run before `file_server`.
- **`/healthz` returns the app shell:** keep the `respond /healthz 200` inside
  the `route` block before `try_files`, as in `deploy/Caddyfile`.
- **Assets are not cached long-term:** confirm the request path starts with
  `/assets/` and the build output is content-hashed.
- **Config error on load:** validate `config.json` against the schema in
  `web/src/app/config.ts`; unknown fields are rejected.
- **Build cannot find `contracts/providers.json`:** build from the repository
  root with `-f deploy/Dockerfile`, not from `web/`.
