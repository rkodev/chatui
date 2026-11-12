# GitHub Pages recipe

GitHub Pages serves static files only and does not support custom response
headers. Use this recipe for a preview or a low-risk deployment, not as the
primary production target when the CSP and cache headers are required.

## Build and publish

```bash
make web-build
rm -rf /tmp/chatui-pages
cp -R web/dist /tmp/chatui-pages
cp /tmp/chatui-pages/index.html /tmp/chatui-pages/404.html
touch /tmp/chatui-pages/.nojekyll
```

Publish the contents of `/tmp/chatui-pages` to the `gh-pages` branch (or use
the `actions/upload-pages-artifact` and `actions/deploy-pages` actions).

The copied `404.html` is the SPA fallback: GitHub Pages serves it for unknown
paths, the app boots, and the client router reads the original URL.

## Limitations

- GitHub Pages cannot set `Content-Security-Policy`, `X-Content-Type-Options`,
  `Referrer-Policy`, `Permissions-Policy`, `Cross-Origin-Opener-Policy`, or
  `Cache-Control`. The app still works, but the deployment does not meet the
  header requirements in spec sections 8.9 and 14.1.
- Project sites (`https://<user>.github.io/<repo>/`) need a subpath base. Use a
  custom domain or a user/organization site at the domain root, or set Vite
  `base` and the router base to `/<repo>/` before building.
- Owner for the header gap: web deployment owner. Use Cloudflare Pages, Netlify,
  Vercel, the container, or ChatUI when the headers are required.
