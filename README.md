# Universal Assistant

Universal Assistant 是一个桌面通用智能助手项目，目标是把桌宠交互、聊天问答、工具调用、实时检索、会话记忆和长期记忆能力整合到一个可扩展的 Agent 应用中。

## 当前能力

- 桌面宠物形象：支持悬浮显示、聊天框展开/收起、右键互动、巡游和动作反馈。
- 大模型聊天：前端采用流式协议渲染回答，支持 Markdown 内容块、执行过程和来源链接。
- 模型切换：支持通过前端选择或输入命令切换当前模型。
- 会话管理：支持新会话、历史会话、会话重命名和删除。
- 消息持久化：会话、消息、执行过程、工具调用和内容块写入 PostgreSQL。
- Agent 执行：支持 ReAct、Plan-and-Solve、Reflection，并设置最大步数和反思轮数限制。
- 工具调用：已封装天气工具，支持当天、未来和过去天气查询。
- 实时检索：支持用户主动开启，也支持 Agent 判断后向用户确认。
- 长期记忆和 RAG 基础：数据库使用 pgvector，已预留长期记忆向量存储。

## 技术栈

### 桌面端

- Electron
- Vue 3
- TypeScript
- Pinia
- Vite
- Markdown-It
- DOMPurify

目录：`universal-assistant-desktop`

### 后端

- Spring Boot 3
- Spring AI Alibaba
- DashScope
- Spring Data JPA
- Flyway
- PostgreSQL + pgvector

目录：`universal-assistant-backend`

## 项目结构

```text
universal-assistant
├── docker-compose.yml
├── universal-assistant-backend
│   ├── src/main/java/com/hao/universalassistantbackend
│   │   ├── config
│   │   ├── controller
│   │   ├── entity
│   │   ├── model
│   │   ├── repository
│   │   ├── service
│   │   └── tools
│   └── src/main/resources/db/migration
└── universal-assistant-desktop
    ├── electron
    │   └── main
    └── src
        ├── app
        ├── components
        ├── features
        ├── services
        └── widgets
```

## 本地启动

### 1. 启动 PostgreSQL

```bash
docker compose up -d postgres
```

数据库默认配置：

- database: `universal_assistant`
- username: `assistant`
- password: `assistant`
- port: `5432`

Flyway 会在后端启动时自动初始化表结构和 `pgvector` 扩展。

### 2. 启动后端

```bash
cd universal-assistant-backend
export DASHSCOPE_API_KEY=your_dashscope_api_key
./mvnw spring-boot:run
```

常用环境变量：

```bash
ASSISTANT_DB_URL=jdbc:postgresql://localhost:5432/universal_assistant
ASSISTANT_DB_USERNAME=assistant
ASSISTANT_DB_PASSWORD=assistant
DASHSCOPE_API_KEY=your_dashscope_api_key
ASSISTANT_CHAT_MAX_TOKENS=2400
ASSISTANT_AGENT_MAX_STEPS=6
ASSISTANT_AGENT_REFLECTION_LIMIT=1
ASSISTANT_MEMORY_ENABLED=true
```

后端默认运行在 `http://localhost:8080`。

### 3. 启动桌面端

```bash
cd universal-assistant-desktop
pnpm install
pnpm dev
```

如需指定后端地址：

```bash
ASSISTANT_BACKEND_URL=http://localhost:8080 pnpm dev
```

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

## 开发约定

- 前端按 `features`、`widgets`、`components`、`services` 分层。
- Electron 主进程按窗口控制器、运动控制、菜单、IPC、文件能力拆分。
- 后端按 controller、service、tools、repository、model、entity 分层。
- Agent 执行必须有最大步数限制，Reflection 必须有轮数限制。
- 工具调用结果、执行过程和来源链接需要作为可恢复数据持久化。
- 流式回答优先使用内容块协议，旧式文本 delta 只作为兼容兜底。

## 当前待完善

- 补充 ESLint/Prettier 和 CI。
- 清理模板文件和系统文件。
- 补充更完整的单元测试和端到端测试。
- 继续细化长期记忆和 RAG 检索策略。
- 完善模型供应商配置和多模型适配。
