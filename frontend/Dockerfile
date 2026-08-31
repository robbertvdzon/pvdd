# syntax=docker/dockerfile:1.7
FROM ghcr.io/cirruslabs/flutter:3.44.0@sha256:46691e311715845de03a3ba4753a475476936805b29431b1f00f1816981033f8 AS build
RUN git -C /sdks/flutter fetch --depth 1 origin ee80f08bbf97172ec030b8751ceab557177a34a6 \
 && git -C /sdks/flutter checkout --detach ee80f08bbf97172ec030b8751ceab557177a34a6 \
 && flutter precache --web \
 && dart --version 2>&1 | grep -q '3.12.2'
WORKDIR /src
COPY pubspec.yaml pubspec.lock ./
RUN flutter pub get
COPY . .
ARG APP_VERSION=0.1.0
ARG GIT_REVISION=0000000000000000000000000000000000000000
ARG BUILD_TIME=1970-01-01T00:00:00Z
ARG ENVIRONMENT=local
ARG GOOGLE_CLIENT_ID=
RUN flutter build web --release --pwa-strategy=none \
    --dart-define=APP_VERSION="${APP_VERSION}" \
    --dart-define=GIT_REVISION="${GIT_REVISION}" \
    --dart-define=BUILD_TIME="${BUILD_TIME}" \
    --dart-define=ENVIRONMENT="${ENVIRONMENT}" \
    --dart-define=GOOGLE_CLIENT_ID="${GOOGLE_CLIENT_ID}"
FROM nginxinc/nginx-unprivileged:1.27-alpine
USER 0
RUN chgrp -R 0 /etc/nginx /usr/share/nginx/html /var/cache/nginx \
 && chmod -R g=u /etc/nginx /usr/share/nginx/html /var/cache/nginx
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/build/web /usr/share/nginx/html
USER 101
EXPOSE 8080
