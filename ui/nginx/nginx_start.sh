#!/bin/sh

if [ ! -z "$MAX_BODY_SIZE" ]
then
    find /etc/nginx/templates/ -type f -exec sed -i "s,client_max_body_size 1m;,client_max_body_size $MAX_BODY_SIZE;," {} \;
fi

if [ ! -z "$REARM_PRODUCT_VERSION" ]
then
    find /usr/share/nginx/html/assets/ -type f -exec sed -i "s|54ab89bb-f1f1-459c-afbf-e4d78655b298|$REARM_PRODUCT_VERSION|" {} \;
fi

# TEA custom domains (TPF-148): default.conf.template uses TEA_CUSTOM_DOMAINS (hostnames separated by
# spaces) as the server_name of its TEA-only server block. nginx refuses an empty server_name, so with no
# hosts listed (unset, empty or blank) the block gets the reserved name tea-custom-domains.invalid
# (RFC 6761: .invalid never resolves), which no request carries. Anything but lowercase hostnames and
# spaces (a comma list, a scheme, a port, a trailing dot) stops the start here, and so do a leading dot
# (an nginx wildcard) and localhost (the main server block's own name, which keeps it): nginx would
# otherwise start with a name that matches nothing or more than listed, and a custom host could get the
# full UI. Exported, because the image entrypoint's envsubst substitutes only defined variables.
case "$TEA_CUSTOM_DOMAINS" in
    *[!\ ]*) ;;
    *) TEA_CUSTOM_DOMAINS=tea-custom-domains.invalid ;;
esac
case " $TEA_CUSTOM_DOMAINS " in
    *[!a-z0-9.\ -]*|*" ."*|*". "*|*" localhost "*)
        echo "nginx_start.sh: TEA_CUSTOM_DOMAINS must be lowercase hostnames separated by spaces (no leading or trailing dot, not localhost); got: $TEA_CUSTOM_DOMAINS" >&2
        exit 1
        ;;
esac
export TEA_CUSTOM_DOMAINS

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
