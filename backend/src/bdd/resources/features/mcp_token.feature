# language: zh-CN
功能: 管理员设置 MCP 数据源 token（P1-10，AES-256-GCM 加密落库）
  管理员在后台为内置数据源设置访问 token：明文只在请求体一次经过，
  服务端加密落库（v1 前缀密文），后续装配按主密钥解密使用。

  场景: 管理员设置 token 后库内为密文且可解密还原
    当 管理员为数据源 "tushare" 设置 token "bdd-plain-token"
    那么 数据源 "tushare" 的库内 token 应为 v1 密文且不含明文 "bdd-plain-token"
    而且 数据源 "tushare" 的 token 解密后应还原为 "bdd-plain-token"
