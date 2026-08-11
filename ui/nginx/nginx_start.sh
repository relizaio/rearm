#!/bin/sh

if [ ! -z "$MAX_BODY_SIZE" ]
then
    find /etc/nginx/templates/ -type f -exec sed -i "s,client_max_body_size 1m;,client_max_body_size $MAX_BODY_SIZE;," {} \;
fi

if [ ! -z "$REARM_PRODUCT_VERSION" ]
then
    find /usr/share/nginx/html/assets/ -type f -exec sed -i "s|54ab89bb-f1f1-459c-afbf-e4d78655b298|$REARM_PRODUCT_VERSION|" {} \;
fi

# run regular entrypoint -- exec, so nginx becomes PID 1 and can be signalled at all.
# Without it this script stays PID 1: the kernel gives PID 1 in a namespace
# SIGNAL_UNKILLABLE and DISCARDS any signal left at SIG_DFL, which a plain sh has, so
# nothing was ever delivered. (docker-entrypoint.sh does exec nginx, but only replaces
# THAT shell, a child of this one.)
#
# What each caller then gets, and they differ:
#   docker/compose -- the base image sets STOPSIGNAL SIGQUIT, which we inherit, so
#     `docker stop` now performs a genuine GRACEFUL drain instead of timing out.
#   kubernetes     -- the kubelet sends SIGTERM regardless, and for nginx SIGTERM means
#     FAST shutdown (SIGQUIT is the graceful one). nginx therefore exits in
#     milliseconds, so the deployment carries a preStop sleep to cover Service-endpoint
#     propagation; without it a rollout would 502 while endpoints still point here.
exec /docker-entrypoint.sh nginx -g "daemon off;"
