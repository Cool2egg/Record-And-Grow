# 构建：Node 24 与本地开发保持一致
FROM node:24-alpine AS build
WORKDIR /app

# 先只拷依赖清单，命中 Docker 层缓存
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# 运行：nginx 托管静态文件，并把 /api 与 /mcp 反代回后端
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
