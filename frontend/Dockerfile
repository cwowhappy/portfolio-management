# 构建阶段
FROM node:22-alpine AS build
RUN corepack enable
WORKDIR /workspace
COPY package.json pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile=false
COPY . .
RUN pnpm build

# 运行阶段（Next standalone）
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /workspace/.next/standalone ./
COPY --from=build /workspace/.next/static ./.next/static
COPY --from=build /workspace/public ./public
EXPOSE 3000
ENV PORT=3000
CMD ["node", "server.js"]
