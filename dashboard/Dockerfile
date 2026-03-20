# syntax=docker/dockerfile:1.7
# Two-stage: node build produces static assets, nginx serves them.

FROM node:20-alpine AS build
WORKDIR /src
COPY dashboard/package.json dashboard/package-lock.json* ./
RUN --mount=type=cache,target=/root/.npm \
    npm install --no-audit --no-fund
COPY dashboard .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /src/dist /usr/share/nginx/html
COPY dashboard/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80/tcp
