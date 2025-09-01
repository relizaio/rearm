#!/bin/sh

if [ ! -z "$MAX_BODY_SIZE" ]
then
    find /etc/nginx/templates/ -type f -exec sed -i "s,client_max_body_size 1m;,client_max_body_size $MAX_BODY_SIZE;," {} \;
fi

if [ ! -z "$REARM_PRODUCT_VERSION" ]
then
    find /usr/share/nginx/html/assets/ -type f -exec sed -i "s,54ab89bb-f1f1-459c-afbf-e4d78655b298,$REARM_PRODUCT_VERSION" {} \;
fi

# run regular entrypoint
/docker-entrypoint.sh nginx -g "daemon off;"