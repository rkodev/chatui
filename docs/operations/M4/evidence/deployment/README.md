# Web deployment evidence

- Date: 2026-11-10
- Host: `ronald-Lenovo-Legion-S7-15IMH5`, Ubuntu 26.04.1 LTS, x86_64
- Docker: 29.9.3, build 5457199
- Server: Caddy 2.11.9 (`caddy:2-alpine`)
- Build image: `node:22-alpine`
- Base image digests:
  - `node:22-alpine@sha256:0e1754758c2df4cfa3e68d59e2e1b7374bb7fd9ea6328eaaacf15cc934af8202`
  - `caddy:2-alpine@sha256:78bd35025c7ba85961dd149ae670315129954e77abe2383ac47c89106b35ade9`
- Exposed port: `80` (host `8080` in the smoke run)
- Build command: `docker build -f deploy/Dockerfile -t chatui-web:local .`
- Run command: `docker run -d --name chatui-web-smoke -p 8080:80 chatui-web:local`

## Build and image

`make web-build` completed successfully. The Docker build completed with the
multi-stage `deploy/Dockerfile`; only `web/dist` is copied into the Caddy stage.

```text
chatui-web:local 6e8460638c59 98.6MB
```

The image environment contains only the base image defaults:

```json
["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin","CADDY_VERSION=v2.11.9","XDG_CONFIG_HOME=/config","XDG_DATA_HOME=/data"]
```

No application environment variables are required.

## Smoke checks

The environment policy denies `curl`, so the same HTTP checks were run with
Node's built-in `fetch` against `http://localhost:8080`. The response headers
below are the actual headers returned by the running container.

### `/` (app shell)

```text
status 200
cache-control: no-cache
content-encoding: gzip
content-length: 606
content-security-policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' https: http://localhost:* http://127.0.0.1:*; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'
content-type: text/html; charset=utf-8
cross-origin-opener-policy: same-origin
permissions-policy: camera=(), microphone=(), geolocation=()
referrer-policy: no-referrer
server: Caddy
x-content-type-options: nosniff
```

### `/config.json`

```text
status 200
cache-control: no-cache
content-encoding: gzip
content-length: 300
content-security-policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' https: http://localhost:* http://127.0.0.1:*; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'
content-type: application/json
cross-origin-opener-policy: same-origin
permissions-policy: camera=(), microphone=(), geolocation=()
referrer-policy: no-referrer
server: Caddy
x-content-type-options: nosniff
```

### `/healthz`

```text
status 200
cache-control: no-cache
content-length: 0
content-security-policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' https: http://localhost:* http://127.0.0.1:*; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'
cross-origin-opener-policy: same-origin
permissions-policy: camera=(), microphone=(), geolocation=()
referrer-policy: no-referrer
server: Caddy
x-content-type-options: nosniff
body-length: 0
```

### `/some/deep/route` (SPA fallback)

```text
status 200
cache-control: no-cache
content-encoding: gzip
content-length: 606
content-security-policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' https: http://localhost:* http://127.0.0.1:*; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'
content-type: text/html; charset=utf-8
cross-origin-opener-policy: same-origin
permissions-policy: camera=(), microphone=(), geolocation=()
referrer-policy: no-referrer
server: Caddy
x-content-type-options: nosniff
body-head: <!doctype html> <html lang="en">   <head>     <meta charset="UTF-8" />     <meta name="viewport" content="width=device-w
```

### `/assets/index-C6EeZYKY.js` (hashed asset)

```text
status 200
cache-control: public, max-age=31536000, immutable
content-encoding: gzip
content-security-policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' https: http://localhost:* http://127.0.0.1:*; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'
content-type: text/javascript; charset=utf-8
cross-origin-opener-policy: same-origin
permissions-policy: camera=(), microphone=(), geolocation=()
referrer-policy: no-referrer
server: Caddy
transfer-encoding: chunked
x-content-type-options: nosniff
```

## Config and secret checks

`/srv/config.json` in the image is the checked-in public config. The app's
`loadConfig` validates it at startup with a strict schema and fails closed on
unknown fields, malformed JSON, or unknown presets. The focused test run passed:

```text
pnpm --dir web exec vitest run src/app/config.test.ts
Test Files  1 passed (1)
Tests       18 passed (18)
```

A scan of the image filesystem for common secret patterns returned no matches:

```text
docker run --rm chatui-web:local sh -c 'grep -RInE "sk-[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{35}|xox[baprs]-|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----" /srv || echo "no secret patterns found"'
no secret patterns found
```

## Docker Compose

`docker compose -f deploy/docker-compose.yml config` resolves the single `web`
service, builds from the repository root with `deploy/Dockerfile`, publishes
`8080:80`, and uses `wget -qO- http://127.0.0.1/healthz` as the healthcheck.

## Hosting targets that cannot reproduce the headers

- GitHub Pages cannot set response headers. The recipe in
  `deploy/github-pages/README.md` documents the SPA fallback and the header gap.
  Owner: web deployment owner. Use Cloudflare Pages, Netlify, Vercel, the
  container, or roksky when the headers are required.
