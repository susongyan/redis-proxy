# Redis Proxy 控制面前端

独立的控制面中后台项目，用于配置发布、版本回滚、路由收敛、整集群切换、namespace/key 治理和观测分析。

## 技术栈

- Vue 3
- Vite
- TypeScript
- Vue Router
- Pinia
- Element Plus
- ECharts
- Axios

## 本地启动

```bash
npm install
npm run dev
```

默认监听 `http://127.0.0.1:5173`，开发环境将 `/api` 和 `/healthz` 代理到 `http://127.0.0.1:8090`。

## 验证

```bash
npm test
npm run build
```

## 页面范围

- 总览 Dashboard
- 配置中心
- 路由与集群调度
- 整集群切换
- Proxy 实例与收敛
- 治理能力
- 观测分析

第一版不实现登录和 RBAC，默认由外部网关或内网访问控制兜底。
