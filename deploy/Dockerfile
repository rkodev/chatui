# syntax=docker/dockerfile:1

# Build the static web bundle. The Vite config imports the shared provider
# contracts from the repository root, so the build stage needs both `web/` and
# `contracts/providers.json`.
FROM node:22-alpine@sha256:0e1754758c2df4cfa3e68d59e2e1b7374bb7fd9ea6328eaaacf15cc934af8202 AS build

WORKDIR /app

RUN corepack enable && corepack prepare pnpm@11.7.0 --activate

COPY web/package.json web/pnpm-lock.yaml ./web/
COPY contracts/providers.json ./contracts/providers.json

RUN pnpm --dir web install --frozen-lockfile

COPY web/tsconfig.json web/tsconfig.app.json web/tsconfig.node.json \
     web/vite.config.ts web/index.html ./web/
COPY web/src ./web/src
COPY web/public ./web/public

RUN pnpm --dir web build

# Serve only the built static files. Caddy handles SPA fallback, cache rules,
# security headers, and the static `/healthz` endpoint.
FROM caddy:2-alpine@sha256:78bd35025c7ba85961dd149ae670315129954e77abe2383ac47c89106b35ade9

COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/web/dist /srv

EXPOSE 80
