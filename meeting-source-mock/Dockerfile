FROM nginxinc/nginx-unprivileged:1.27-alpine
USER 0
RUN chgrp -R 0 /etc/nginx /usr/share/nginx/html /var/cache/nginx \
 && chmod -R g=u /etc/nginx /usr/share/nginx/html /var/cache/nginx
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY fixtures /usr/share/nginx/html/fixtures
USER 101
EXPOSE 8080
