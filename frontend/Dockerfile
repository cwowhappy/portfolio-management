# 构建阶段
FROM node:22-alpine AS build
ENV CI=true
RUN corepack enable
WORKDIR /workspace
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
ARG NPM_REGISTRY=https://registry.npmjs.org/
RUN pnpm install --frozen-lockfile=true --registry=${NPM_REGISTRY}
COPY . .
RUN mkdir -p public
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
