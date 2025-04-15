#!/bin/sh

if [ ! -z "$MAX_BODY_SIZE" ]
then
    find /etc/nginx/templates/ -type f -exec sed -i "s,client_max_body_size 1m;,client_max_body_size $MAX_BODY_SIZE;," {} \;
fi


# run regular entrypoint
/docker-entrypoint.sh nginx -g "daemon off;"