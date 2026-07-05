# Static Astro build served by nginx. For sandbox hot-swap-style deploys the
# site is built on the host (npm run build) and dist/ is copied in; CI can
# later switch to a multi-stage build matching landing_site's convention.
FROM nginx:1.29-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY dist /usr/share/nginx/html
