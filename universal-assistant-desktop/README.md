# Universal Assistant Desktop

这是 Universal Assistant 的桌面端应用，基于 Electron、Vue 3、TypeScript、Pinia 和 Vite 构建。

## 职责范围

- 桌宠窗口：展示宠物形象、互动动作、随机巡游和右键菜单。
- 聊天窗口：提供聊天、会话历史、消息编辑重发、文件面板、模型选择和实时检索开关。
- 流式渲染：消费后端 `application/x-ndjson` 流式事件，渲染 Markdown、执行过程、来源链接和状态。
- Electron 能力：窗口控制、桌宠跟随、文件读取/写入/转换、右键编辑菜单。

## 目录结构

```text
universal-assistant-desktop
├── electron
│   ├── main.ts
│   ├── preload.ts
│   └── main
│       ├── assistantIpc.ts
│       ├── assistantWindowController.ts
│       ├── chatWindowController.ts
│       ├── petWindowController.ts
│       ├── petMotionController.ts
│       ├── petMenu.ts
│       └── windowMotion.ts
└── src
    ├── app
    ├── components
    ├── features
    │   ├── chat
    │   ├── conversations
    │   └── files
    ├── services
    └── widgets
```

## 开发命令

```bash
pnpm install
pnpm dev
```

指定后端地址：

```bash
ASSISTANT_BACKEND_URL=http://localhost:8080 pnpm dev
```

## 验证命令

```bash
./node_modules/.bin/vue-tsc --noEmit
./node_modules/.bin/vite build
```

完整打包：

```bash
pnpm build
```

## 模块约定

- `components` 放跨业务通用组件。
- `features` 放业务模块和状态管理。
- `widgets` 放可组合的大块 UI。
- `services` 放 API 封装。
- `electron/main` 放主进程能力模块。

流式聊天相关职责：

- `useChatStreaming.ts`：聊天发送、编辑重发和模块装配。
- `chatStreamRunner.ts`：请求生命周期、停止生成和工具确认。
- `chatStreamEvents.ts`：流式事件归并和内容块更新。
- `chatDraftPreparation.ts`：模型命令解析。
- `chatCancellation.ts`：回答中断状态处理。

## 注意事项

- 不随意修改 `preload.ts` 暴露的 API 名称。
- 不随意修改后端流式事件类型。
- `block_delta` 是当前主路径，旧式 `delta` 仅作为兼容兜底。
- 桌宠运动逻辑在 `petMotionController.ts`，窗口创建在 `petWindowController.ts`。
