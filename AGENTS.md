# AGENTS.md

## 沟通方式

- 默认使用中文回复。
- 代码、命令、变量名、文件路径保持英文。
- 结论先行，简洁直接。
- 不夸张承诺，不回避问题；发现方案风险时直接说明。

## 项目目标

Universal Assistant 是一个桌面通用智能助手 Agent 项目，当前包含：

- Electron + Vue 3 + TypeScript 桌面端。
- Spring Boot + Spring AI Alibaba 后端。
- PostgreSQL + pgvector 数据存储。
- 桌宠交互、流式聊天、实时检索、工具调用、会话记忆和长期记忆能力。

## 目录边界

- `universal-assistant-desktop`：桌面端和 Electron 主进程。
- `universal-assistant-backend`：后端服务、Agent 编排、工具调用和持久化。
- `docker-compose.yml`：本地 PostgreSQL/pgvector。

## 前端分层约定

- `src/app`：应用入口和全局装配。
- `src/components`：跨业务通用组件。
- `src/features`：业务能力模块，例如聊天、会话、文件。
- `src/widgets`：较完整的界面组合，例如聊天面板、宠物形象。
- `src/services`：HTTP API、Electron preload API 的前端封装。
- `electron/main`：Electron 主进程模块。

拆分原则：

- 页面组件优先做组合，不承载复杂业务。
- Pinia store 只保存状态和轻量派生逻辑。
- 请求、流式事件、消息编辑、滚动、文件操作等逻辑放到 composable/model。
- 单个 Vue 组件原则上不超过 350 行；CSS 动画文件可例外。
- Electron 主进程 IPC 名称和 preload API 兼容性优先，不随意改名。

## 后端分层约定

- `controller`：HTTP 接口和流式响应出口。
- `service`：业务编排、Agent 执行、持久化协调。
- `tools`：Spring AI 工具封装。
- `repository`：数据库访问。
- `entity`：JPA 实体。
- `model`：请求响应 DTO 和领域数据结构。

Agent 约定：

- ReAct、Plan-and-Solve、Reflection 必须设置最大步数或轮数限制。
- 工具调用必须记录到 `tool_calls`。
- Agent 执行过程必须记录到 `agent_runs` / `agent_steps`。
- 流式输出过程中，应尽量持久化可恢复的中间结果。
- 天气等外部工具失败时，需要有实时检索或降级回答路径。

## 验证命令

前端：

```bash
cd universal-assistant-desktop
./node_modules/.bin/vue-tsc --noEmit
./node_modules/.bin/vite build
```

后端：

```bash
cd universal-assistant-backend
./mvnw test
```

## Git 与红线操作

- 不自动 `git commit` 或 `git push`，除非用户明确要求。
- 提交前先展示将要提交的变更摘要。
- 不执行 `git reset --hard`、`git rebase`、强制推送等破坏性操作，除非用户明确确认。
- 删除文件、目录或 git 历史前必须先询问用户。
- 修改 `.env`、密钥、token、证书、CI/CD 配置前必须先询问用户。
- 不公开发布 npm 包、镜像或生产部署，除非用户明确要求。
