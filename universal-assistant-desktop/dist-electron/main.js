import { app, BrowserWindow, screen, Menu, ipcMain, dialog } from "electron";
import { readFile, stat, writeFile, access } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { constants } from "node:fs";
const __dirname$1 = path.dirname(fileURLToPath(import.meta.url));
process.env.APP_ROOT = path.join(__dirname$1, "..");
const VITE_DEV_SERVER_URL = process.env["VITE_DEV_SERVER_URL"];
const MAIN_DIST = path.join(process.env.APP_ROOT, "dist-electron");
const RENDERER_DIST = path.join(process.env.APP_ROOT, "dist");
process.env.VITE_PUBLIC = VITE_DEV_SERVER_URL ? path.join(process.env.APP_ROOT, "public") : RENDERER_DIST;
const BACKEND_URL = process.env["ASSISTANT_BACKEND_URL"] || "http://localhost:8080";
const MAX_TEXT_FILE_SIZE = 5 * 1024 * 1024;
const WINDOW_GAP = 14;
const FOLLOW_STEP_MS = 16;
const FOLLOW_STIFFNESS = 0.2;
const ROAM_MIN_DELAY_MS = 8e3;
const ROAM_MAX_DELAY_MS = 2e4;
const ROAM_MIN_DURATION_MS = 900;
const ROAM_MAX_DURATION_MS = 1800;
const TEXT_EXTENSIONS = /* @__PURE__ */ new Set([
  ".txt",
  ".md",
  ".markdown",
  ".json",
  ".csv",
  ".tsv",
  ".xml",
  ".html",
  ".css",
  ".js",
  ".ts",
  ".vue",
  ".java",
  ".properties",
  ".yml",
  ".yaml"
]);
let petWindow = null;
let chatWindow = null;
let currentPetState = "idle";
let suppressPetMoveSync = false;
let suppressChatMoveSync = false;
let releasePetMoveSyncTimer = null;
let releaseChatMoveSyncTimer = null;
let petFollowTarget = null;
let petFollowTimer = null;
let petManualControlUntil = 0;
let petPointerActive = false;
let petRoamTimer = null;
const windowAnimationTimers = /* @__PURE__ */ new Map();
const grantedFiles = /* @__PURE__ */ new Set();
const grantedDirectories = /* @__PURE__ */ new Set();
function createWindowOptions() {
  return {
    webPreferences: {
      preload: path.join(__dirname$1, "preload.mjs"),
      contextIsolation: true,
      nodeIntegration: false
    }
  };
}
function rendererUrl(view) {
  if (VITE_DEV_SERVER_URL) {
    return `${VITE_DEV_SERVER_URL}?view=${view}`;
  }
  return {
    file: path.join(RENDERER_DIST, "index.html"),
    query: { view }
  };
}
function loadRenderer(window, view) {
  const target = rendererUrl(view);
  if (typeof target === "string") {
    void window.loadURL(target);
    return;
  }
  void window.loadFile(target.file, { query: target.query });
}
function createPetWindow() {
  const workArea = screen.getPrimaryDisplay().workArea;
  petWindow = new BrowserWindow({
    width: 168,
    height: 196,
    x: workArea.x + workArea.width - 220,
    y: workArea.y + workArea.height - 260,
    frame: false,
    transparent: true,
    resizable: false,
    maximizable: false,
    minimizable: false,
    fullscreenable: false,
    focusable: false,
    acceptFirstMouse: true,
    alwaysOnTop: true,
    skipTaskbar: true,
    hasShadow: false,
    backgroundColor: "#00000000",
    show: false,
    ...createWindowOptions()
  });
  petWindow.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
  petWindow.setAlwaysOnTop(true, "floating");
  petWindow.once("ready-to-show", () => {
    petWindow == null ? void 0 : petWindow.show();
    schedulePetRoam();
  });
  petWindow.webContents.on("did-finish-load", sendCurrentPetState);
  petWindow.on("move", syncChatWindowToPetWindow);
  petWindow.on("closed", () => {
    stopPetFollow();
    cancelPetRoam();
    clearMoveSyncSuppressions();
    petWindow = null;
  });
  loadRenderer(petWindow, "pet");
}
function createChatWindow() {
  chatWindow = new BrowserWindow({
    width: 440,
    height: 640,
    minWidth: 380,
    minHeight: 520,
    frame: false,
    transparent: false,
    resizable: true,
    show: false,
    backgroundColor: "#f7f5ef",
    title: "Universal Assistant",
    ...createWindowOptions()
  });
  chatWindow.setMenuBarVisibility(false);
  registerEditorContextMenu(chatWindow);
  chatWindow.on("show", () => {
    cancelPetRoam();
    stopPetFollow();
  });
  chatWindow.on("hide", () => {
    stopPetFollow();
    schedulePetRoam();
  });
  chatWindow.on("move", syncPetWindowToChatWindow);
  chatWindow.on("resize", syncPetWindowToChatWindow);
  chatWindow.on("closed", () => {
    stopPetFollow();
    clearMoveSyncSuppressions();
    chatWindow = null;
    schedulePetRoam();
  });
  loadRenderer(chatWindow, "chat");
}
function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}
function randomBetween(min, max) {
  return min + Math.random() * (max - min);
}
function clearMoveSyncSuppressions() {
  if (releasePetMoveSyncTimer) {
    clearTimeout(releasePetMoveSyncTimer);
    releasePetMoveSyncTimer = null;
  }
  if (releaseChatMoveSyncTimer) {
    clearTimeout(releaseChatMoveSyncTimer);
    releaseChatMoveSyncTimer = null;
  }
  suppressPetMoveSync = false;
  suppressChatMoveSync = false;
}
function cancelWindowAnimation(window) {
  const timer = windowAnimationTimers.get(window.id);
  if (timer) {
    clearTimeout(timer);
    windowAnimationTimers.delete(window.id);
  }
}
function suppressMoveSyncFor(window) {
  if (window === petWindow) {
    suppressPetMoveSync = true;
    if (releasePetMoveSyncTimer) {
      clearTimeout(releasePetMoveSyncTimer);
    }
    releasePetMoveSyncTimer = setTimeout(() => {
      suppressPetMoveSync = false;
      releasePetMoveSyncTimer = null;
    }, 32);
    return;
  }
  if (window === chatWindow) {
    suppressChatMoveSync = true;
    if (releaseChatMoveSyncTimer) {
      clearTimeout(releaseChatMoveSyncTimer);
    }
    releaseChatMoveSyncTimer = setTimeout(() => {
      suppressChatMoveSync = false;
      releaseChatMoveSyncTimer = null;
    }, 32);
  }
}
function setWindowPosition(window, x, y) {
  suppressMoveSyncFor(window);
  window.setPosition(Math.round(x), Math.round(y));
}
function easeOutCubic(progress) {
  return 1 - Math.pow(1 - progress, 3);
}
function animateWindowTo(window, targetX, targetY, duration = 180, onComplete) {
  cancelWindowAnimation(window);
  if (window.isDestroyed()) {
    return;
  }
  const start = window.getBounds();
  const distance = Math.hypot(targetX - start.x, targetY - start.y);
  if (distance < 4) {
    setWindowPosition(window, targetX, targetY);
    onComplete == null ? void 0 : onComplete();
    return;
  }
  const startedAt = Date.now();
  const tick = () => {
    if (window.isDestroyed()) {
      windowAnimationTimers.delete(window.id);
      return;
    }
    const elapsed = Date.now() - startedAt;
    const progress = clamp(elapsed / duration, 0, 1);
    const eased = easeOutCubic(progress);
    const nextX = start.x + (targetX - start.x) * eased;
    const nextY = start.y + (targetY - start.y) * eased;
    setWindowPosition(window, nextX, nextY);
    if (progress >= 1) {
      windowAnimationTimers.delete(window.id);
      onComplete == null ? void 0 : onComplete();
      return;
    }
    windowAnimationTimers.set(window.id, setTimeout(tick, 16));
  };
  tick();
}
function moveWindowTo(window, x, y, animated = false) {
  if (animated) {
    animateWindowTo(window, x, y);
    return;
  }
  cancelWindowAnimation(window);
  setWindowPosition(window, x, y);
}
function getChatTargetForPet() {
  if (!petWindow || !chatWindow) {
    return null;
  }
  const chatBounds = chatWindow.getBounds();
  const petBounds = petWindow.getBounds();
  const workArea = screen.getDisplayMatching(chatBounds).workArea;
  let x = chatBounds.x + chatBounds.width + WINDOW_GAP;
  if (x + petBounds.width > workArea.x + workArea.width - WINDOW_GAP) {
    x = chatBounds.x - petBounds.width - WINDOW_GAP;
  }
  const y = clamp(
    chatBounds.y + chatBounds.height - petBounds.height,
    workArea.y + WINDOW_GAP,
    workArea.y + workArea.height - petBounds.height - WINDOW_GAP
  );
  return {
    x: clamp(x, workArea.x + WINDOW_GAP, workArea.x + workArea.width - petBounds.width - WINDOW_GAP),
    y
  };
}
function stopPetFollow() {
  petFollowTarget = null;
  if (petFollowTimer) {
    clearTimeout(petFollowTimer);
    petFollowTimer = null;
  }
}
function tickPetFollow() {
  petFollowTimer = null;
  if (!petFollowTarget || !petWindow || petWindow.isDestroyed() || !(chatWindow == null ? void 0 : chatWindow.isVisible())) {
    stopPetFollow();
    return;
  }
  if (petPointerActive || Date.now() < petManualControlUntil) {
    petFollowTimer = setTimeout(tickPetFollow, 120);
    return;
  }
  const petBounds = petWindow.getBounds();
  const deltaX = petFollowTarget.x - petBounds.x;
  const deltaY = petFollowTarget.y - petBounds.y;
  const distance = Math.hypot(deltaX, deltaY);
  if (distance < 1) {
    setWindowPosition(petWindow, petFollowTarget.x, petFollowTarget.y);
    stopPetFollow();
    return;
  }
  setWindowPosition(petWindow, petBounds.x + deltaX * FOLLOW_STIFFNESS, petBounds.y + deltaY * FOLLOW_STIFFNESS);
  petFollowTimer = setTimeout(tickPetFollow, FOLLOW_STEP_MS);
}
function startPetFollow() {
  if (!petFollowTimer) {
    petFollowTimer = setTimeout(tickPetFollow, FOLLOW_STEP_MS);
  }
}
function updatePetFollowTarget() {
  if (!(chatWindow == null ? void 0 : chatWindow.isVisible()) || petPointerActive || Date.now() < petManualControlUntil) {
    return;
  }
  const target = getChatTargetForPet();
  if (!target) {
    return;
  }
  cancelPetRoam();
  petFollowTarget = target;
  startPetFollow();
}
function canPetRoam() {
  return Boolean(
    petWindow && !petWindow.isDestroyed() && !(chatWindow == null ? void 0 : chatWindow.isVisible()) && !petPointerActive && Date.now() >= petManualControlUntil
  );
}
function cancelPetRoam() {
  if (petRoamTimer) {
    clearTimeout(petRoamTimer);
    petRoamTimer = null;
  }
  if (petWindow) {
    cancelWindowAnimation(petWindow);
  }
}
function schedulePetRoam() {
  cancelPetRoam();
  if (!canPetRoam()) {
    return;
  }
  petRoamTimer = setTimeout(startPetRoam, randomBetween(ROAM_MIN_DELAY_MS, ROAM_MAX_DELAY_MS));
}
function startPetRoam() {
  petRoamTimer = null;
  if (!canPetRoam() || !petWindow) {
    schedulePetRoam();
    return;
  }
  const petBounds = petWindow.getBounds();
  const workArea = screen.getDisplayMatching(petBounds).workArea;
  const x = Math.round(randomBetween(workArea.x + WINDOW_GAP, workArea.x + workArea.width - petBounds.width - WINDOW_GAP));
  const y = Math.round(randomBetween(workArea.y + WINDOW_GAP, workArea.y + workArea.height - petBounds.height - WINDOW_GAP));
  const duration = Math.round(randomBetween(ROAM_MIN_DURATION_MS, ROAM_MAX_DURATION_MS));
  setPetState("running");
  animateWindowTo(petWindow, x, y, duration, () => {
    if (!canPetRoam()) {
      return;
    }
    const nextState = Math.random() > 0.55 ? "curious" : Math.random() > 0.35 ? "happy" : "sleepy";
    setPetState(nextState);
    setTimeout(() => {
      if (canPetRoam()) {
        setPetState("idle");
        schedulePetRoam();
      }
    }, Math.round(randomBetween(1200, 2600)));
  });
}
function positionChatWindow(options = {}) {
  if (!petWindow || !chatWindow) {
    return;
  }
  const petBounds = petWindow.getBounds();
  const chatBounds = chatWindow.getBounds();
  const workArea = screen.getDisplayMatching(petBounds).workArea;
  let x = petBounds.x - chatBounds.width - WINDOW_GAP;
  if (x < workArea.x) {
    x = petBounds.x + petBounds.width + WINDOW_GAP;
  }
  const y = clamp(
    petBounds.y + petBounds.height - chatBounds.height,
    workArea.y + WINDOW_GAP,
    workArea.y + workArea.height - chatBounds.height - WINDOW_GAP
  );
  moveWindowTo(
    chatWindow,
    clamp(x, workArea.x + WINDOW_GAP, workArea.x + workArea.width - chatBounds.width - WINDOW_GAP),
    y,
    Boolean(options.animated)
  );
}
function syncChatWindowToPetWindow() {
  if (suppressPetMoveSync || !(chatWindow == null ? void 0 : chatWindow.isVisible())) {
    return;
  }
  positionChatWindow();
}
function syncPetWindowToChatWindow() {
  if (suppressChatMoveSync || !(chatWindow == null ? void 0 : chatWindow.isVisible())) {
    return;
  }
  updatePetFollowTarget();
}
function movePetWindowBy(deltaX, deltaY) {
  if (!petWindow || typeof deltaX !== "number" || typeof deltaY !== "number") {
    return;
  }
  petManualControlUntil = Date.now() + 900;
  stopPetFollow();
  cancelPetRoam();
  const petBounds = petWindow.getBounds();
  const workArea = screen.getDisplayMatching(petBounds).workArea;
  const x = clamp(petBounds.x + deltaX, workArea.x, workArea.x + workArea.width - petBounds.width);
  const y = clamp(petBounds.y + deltaY, workArea.y, workArea.y + workArea.height - petBounds.height);
  moveWindowTo(petWindow, x, y);
  if (chatWindow == null ? void 0 : chatWindow.isVisible()) {
    positionChatWindow();
  }
}
function setPetPointerActive(active) {
  petPointerActive = Boolean(active);
  if (petPointerActive) {
    petManualControlUntil = Date.now() + 900;
    stopPetFollow();
    cancelPetRoam();
    return;
  }
  petManualControlUntil = Date.now() + 900;
  if (!(chatWindow == null ? void 0 : chatWindow.isVisible())) {
    schedulePetRoam();
  }
}
function toggleChatWindow() {
  if (!chatWindow) {
    createChatWindow();
  }
  if (!chatWindow) {
    return;
  }
  if (chatWindow.isVisible()) {
    chatWindow.hide();
    setPetState("idle");
    schedulePetRoam();
    return;
  }
  cancelPetRoam();
  stopPetFollow();
  setPetState("idle");
  positionChatWindow({ animated: true });
  chatWindow.show();
  chatWindow.focus();
}
function sendCurrentPetState() {
  if (!petWindow || petWindow.isDestroyed()) {
    return;
  }
  petWindow.webContents.send("assistant:pet-state", currentPetState);
}
function setPetState(state) {
  if (state !== "idle" && state !== "thinking" && state !== "speaking" && state !== "happy" && state !== "curious" && state !== "sleepy" && state !== "running" && state !== "error") {
    return;
  }
  currentPetState = state;
  sendCurrentPetState();
}
function runEditAction(action, webContents) {
  var _a;
  const target = webContents ?? ((_a = BrowserWindow.getFocusedWindow()) == null ? void 0 : _a.webContents);
  if (!target || target.isDestroyed()) {
    return;
  }
  switch (action) {
    case "undo":
      target.undo();
      break;
    case "redo":
      target.redo();
      break;
    case "cut":
      target.cut();
      break;
    case "copy":
      target.copy();
      break;
    case "paste":
      target.paste();
      break;
    case "selectAll":
      target.selectAll();
      break;
  }
}
function createEditMenuItem(label, action, accelerator, enabled, webContents) {
  return {
    label,
    accelerator,
    enabled,
    click: () => runEditAction(action, webContents)
  };
}
function createEditMenuItems(params, webContents) {
  const editable = (params == null ? void 0 : params.isEditable) ?? true;
  const hasSelection = Boolean(params == null ? void 0 : params.selectionText);
  const editFlags = params == null ? void 0 : params.editFlags;
  if (!editable) {
    return [
      createEditMenuItem("复制 Copy", "copy", "CmdOrCtrl+C", hasSelection, webContents),
      { type: "separator" },
      createEditMenuItem("全选 Select All", "selectAll", "CmdOrCtrl+A", true, webContents)
    ];
  }
  return [
    createEditMenuItem("撤销 Undo", "undo", "CmdOrCtrl+Z", editFlags ? editFlags.canUndo : true, webContents),
    createEditMenuItem("重做 Redo", "redo", "Shift+CmdOrCtrl+Z", editFlags ? editFlags.canRedo : true, webContents),
    { type: "separator" },
    createEditMenuItem("剪切 Cut", "cut", "CmdOrCtrl+X", editFlags ? editFlags.canCut : true, webContents),
    createEditMenuItem("复制 Copy", "copy", "CmdOrCtrl+C", editFlags ? editFlags.canCopy : true, webContents),
    createEditMenuItem("粘贴 Paste", "paste", "CmdOrCtrl+V", editFlags ? editFlags.canPaste : true, webContents),
    { type: "separator" },
    createEditMenuItem("全选 Select All", "selectAll", "CmdOrCtrl+A", true, webContents)
  ];
}
function registerApplicationMenu() {
  const template = [
    {
      label: "编辑 Edit",
      submenu: createEditMenuItems()
    }
  ];
  if (process.platform === "darwin") {
    template.unshift({ role: "appMenu" });
  }
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}
function registerEditorContextMenu(window) {
  window.webContents.on("context-menu", (_event, params) => {
    const items = createEditMenuItems(params, window.webContents);
    if (items.length === 0) {
      return;
    }
    Menu.buildFromTemplate(items).popup({ window });
  });
}
async function normalizePath(filePath) {
  return path.resolve(filePath);
}
function isPathGranted(filePath) {
  if (grantedFiles.has(filePath)) {
    return true;
  }
  for (const directory of grantedDirectories) {
    if (filePath === directory || filePath.startsWith(`${directory}${path.sep}`)) {
      return true;
    }
  }
  return false;
}
async function assertReadableTextFile(filePath) {
  const normalized = await normalizePath(filePath);
  if (!isPathGranted(normalized)) {
    throw new Error("File access was not granted from the picker.");
  }
  const info = await stat(normalized);
  if (!info.isFile()) {
    throw new Error("Only files can be read.");
  }
  if (info.size > MAX_TEXT_FILE_SIZE) {
    throw new Error("Text files larger than 5 MB are not supported in this MVP.");
  }
  const extension = path.extname(normalized).toLowerCase();
  if (!TEXT_EXTENSIONS.has(extension)) {
    throw new Error(`Unsupported text file type: ${extension || "unknown"}`);
  }
  return normalized;
}
async function assertWritableTextFile(filePath) {
  const normalized = await normalizePath(filePath);
  if (!isPathGranted(normalized)) {
    throw new Error("File access was not granted from the picker.");
  }
  const extension = path.extname(normalized).toLowerCase();
  if (!TEXT_EXTENSIONS.has(extension)) {
    throw new Error(`Unsupported text file type: ${extension || "unknown"}`);
  }
  await access(path.dirname(normalized), constants.W_OK);
  return normalized;
}
function convertText(content, targetFormat) {
  switch (targetFormat) {
    case "txt":
      return content;
    case "md":
      return content.startsWith("# ") ? content : `# Converted Document

${content}`;
    case "json":
      try {
        return JSON.stringify(JSON.parse(content), null, 2);
      } catch {
        return JSON.stringify({ content }, null, 2);
      }
    case "csv": {
      return content.split(/\r?\n/).filter(Boolean).map((line) => `"${line.replace(/"/g, '""')}"`).join("\n");
    }
    default:
      throw new Error(`Unsupported target format: ${targetFormat}`);
  }
}
function registerIpcHandlers() {
  ipcMain.handle("assistant:toggle-chat", () => toggleChatWindow());
  ipcMain.handle("assistant:hide-chat", () => {
    chatWindow == null ? void 0 : chatWindow.hide();
    setPetState("idle");
    schedulePetRoam();
  });
  ipcMain.handle("assistant:get-backend-url", () => BACKEND_URL);
  ipcMain.handle("assistant:set-pet-state", (_event, state) => setPetState(state));
  ipcMain.handle("assistant:set-pet-pointer-active", (_event, active) => setPetPointerActive(active));
  ipcMain.handle(
    "assistant:move-pet-by",
    (_event, payload) => movePetWindowBy(payload == null ? void 0 : payload.deltaX, payload == null ? void 0 : payload.deltaY)
  );
  ipcMain.handle("file:select", async (_event, options) => {
    const properties = [
      (options == null ? void 0 : options.directory) ? "openDirectory" : "openFile"
    ];
    if (options == null ? void 0 : options.multiple) {
      properties.push("multiSelections");
    }
    const result = await dialog.showOpenDialog({
      title: (options == null ? void 0 : options.directory) ? "Select a folder" : "Select a text file",
      properties,
      filters: (options == null ? void 0 : options.directory) ? void 0 : [
        {
          name: "Text files",
          extensions: [...TEXT_EXTENSIONS].map((extension) => extension.slice(1))
        }
      ]
    });
    if (result.canceled) {
      return { canceled: true, paths: [] };
    }
    const normalizedPaths = await Promise.all(result.filePaths.map((filePath) => normalizePath(filePath)));
    for (const selectedPath of normalizedPaths) {
      if (options == null ? void 0 : options.directory) {
        grantedDirectories.add(selectedPath);
      } else {
        grantedFiles.add(selectedPath);
      }
    }
    return { canceled: false, paths: normalizedPaths };
  });
  ipcMain.handle("file:read-text", async (_event, filePath) => {
    const normalized = await assertReadableTextFile(filePath);
    const content = await readFile(normalized, "utf8");
    const info = await stat(normalized);
    return {
      path: normalized,
      name: path.basename(normalized),
      size: info.size,
      content
    };
  });
  ipcMain.handle("file:write-text", async (_event, payload) => {
    const normalized = await assertWritableTextFile(payload.filePath);
    await writeFile(normalized, payload.content, "utf8");
    return { path: normalized, name: path.basename(normalized) };
  });
  ipcMain.handle("file:save-text-as", async (_event, payload) => {
    const result = await dialog.showSaveDialog({
      title: "Save text file",
      defaultPath: payload.defaultPath,
      filters: [{ name: "Text files", extensions: [...TEXT_EXTENSIONS].map((extension) => extension.slice(1)) }]
    });
    if (result.canceled || !result.filePath) {
      return { canceled: true };
    }
    const normalized = await normalizePath(result.filePath);
    await access(path.dirname(normalized), constants.W_OK);
    await writeFile(normalized, payload.content, "utf8");
    grantedFiles.add(normalized);
    return { canceled: false, path: normalized, name: path.basename(normalized) };
  });
  ipcMain.handle("file:convert-text", async (_event, payload) => {
    const normalized = await assertReadableTextFile(payload.filePath);
    const content = await readFile(normalized, "utf8");
    const converted = convertText(content, payload.targetFormat);
    const outputPath = normalized.replace(/\.[^/.]+$/, `.${payload.targetFormat}`);
    return {
      sourcePath: normalized,
      defaultPath: outputPath === normalized ? `${normalized}.${payload.targetFormat}` : outputPath,
      content: converted,
      targetFormat: payload.targetFormat
    };
  });
}
app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
    petWindow = null;
    chatWindow = null;
  }
});
app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createPetWindow();
    createChatWindow();
  }
});
app.whenReady().then(() => {
  registerApplicationMenu();
  registerIpcHandlers();
  createPetWindow();
  createChatWindow();
});
export {
  MAIN_DIST,
  RENDERER_DIST,
  VITE_DEV_SERVER_URL
};
