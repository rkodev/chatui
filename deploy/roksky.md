# roksky hosting recipe

roksky is the managed hosting entry point described in spec section 14.3. The
tenant serves the same static bundle as the container; no server-side prompt
proxying or key handling is involved.

## Tenant setup

1. Build the static bundle with `make web-build`.
2. Upload `web/dist` to the tenant's static origin.
3. Choose a tenant subdomain (for example `chat.<tenant>.roksky.example`) and,
   optionally, attach a custom domain. roksky manages TLS.
4. Apply the shared header and cache rules from `deploy/Caddyfile` at the edge:
   SPA fallback to `index.html`, `no-cache` for the shell and `config.json`,
   immutable caching for `/assets/*`, the CSP and security headers, and a static
   `/healthz` 200.
5. Optionally enable the managed storage partner for encrypted backups. The
   partner never receives provider keys, plaintext chats, or backup
   passphrases.
6. Brand the tenant with a public `config.json` (`appName`, `defaultTheme`,
   `enabledPresets`, links, and feature flags). Never add keys or secrets.

## Verification

Run the same smoke checks as the container recipe against the tenant URL:

```bash
curl -sSI https://chat.<tenant>.roksky.example/
curl -sSI https://chat.<tenant>.roksky.example/config.json
curl -sSI https://chat.<tenant>.roksky.example/healthz
curl -sS https://chat.<tenant>.roksky.example/some/deep/route | head
```

Record the tenant URL, header output, and any edge-specific deviation in
`docs/operations/M4/evidence/deployment/`.
