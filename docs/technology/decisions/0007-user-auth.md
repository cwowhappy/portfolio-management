# ADR-0007 用户管理与认证方案

- 状态：已接受（2026-08-21）
- 决策者：项目负责人

## 背景

系统一期无任何用户/认证体系，行情台与 AI 对话全站公开。AI 对话消耗 LLM token，需要
**资格管控**：注册需管理员审核通过后才能使用 AI；行情数据本身公开，保持免登录可访问。
由此引入首个用户体系与持久化存储（PostgreSQL，配套 Flyway）。

## 决策

1. **认证**：Spring Security 会话认证。JSESSIONID cookie 设置为 `HttpOnly` + `SameSite=Lax`，
   状态存服务端会话。
2. **记住我**：勾选"记住我"时签发 30 天 remember-me 令牌（存 `persistent_logins` 表）；
   未勾选仅会话 cookie，浏览器关闭即失效。
3. **密码**：BCrypt 哈希存储；策略 ≥8 位且含字母 + 数字（前后端双重校验）。
4. **用户状态机**：`PENDING`（注册待审）/ `APPROVED`（通过）/ `REJECTED`（拒绝）。
   登录须 `status=APPROVED AND enabled=true`；停用 = `enabled=false`，即时撤销 AI 资格。
   被拒用户可重新注册，**更新复用原 REJECTED 行**置回 PENDING（用户名复用，不产生垃圾数据）。
5. **角色**：`ADMIN` / `USER`。管理员操作接口按角色保护，且禁止对 ADMIN 账号审核/停用。
6. **管理员引导**：启动时从 env（`ADMIN_USERNAME`/`ADMIN_PASSWORD`）**幂等种子**一个
   ADMIN/APPROVED 账号（不存在才创建），避免手工造号。
7. **CSRF 关闭**：本项目为同源 JSON API（前端全部经 Next.js 反代），会话 cookie `SameSite=Lax`
   使跨站状态变更请求不携带 cookie，CSRF 风险可控；关闭 Spring Security CSRF 以简化集成。

## 后果

正面：Spring Security 会话认证 + remember-me 是标准组合，角色化、可扩展；env 种子零人工；
关闭 CSRF 减少回调配置。
风险：服务端会话使后端有状态（会话存储于内存，多副本需共享存储或改 JWT）；`SameSite=Lax`
仅缓解跨站 POST，如需更强保护可后续补 CSRF 令牌。
