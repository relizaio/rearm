#!/bin/sh

# substitute host for keycloak logout
if [ ! -z "$PROJECT_ORIGIN" ]
then
    find /usr/share/nginx/html/ -type f -exec sed -i "s,https://test.relizahub.com,$PROJECT_ORIGIN," {} \;
fi

if [ ! -z "$MAX_BODY_SIZE" ]
then
    find /etc/nginx/templates/ -type f -exec sed -i "s,client_max_body_size 1m;,client_max_body_size $MAX_BODY_SIZE;," {} \;
fi


# run regular entrypoint
/docker-entrypoint.sh nginx -g "daemon off;"