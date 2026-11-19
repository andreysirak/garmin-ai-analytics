FROM node:22-alpine AS build
WORKDIR /app
COPY garmin-chat/frontend/package.json garmin-chat/frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY garmin-chat/frontend/index.html garmin-chat/frontend/vite.config.js ./
COPY garmin-chat/frontend/src ./src
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.28-alpine
ENV API_PROXY_TIMEOUT_SECONDS=690
COPY --from=build /app/dist /usr/share/nginx/html
COPY garmin-chat/frontend/nginx.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 8080
