import { app, BrowserWindow, screen, Menu, ipcMain, dialog, shell } from "electron";
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
const CHAT_WINDOW_WIDTH = 440;
const CHAT_WINDOW_HEIGHT = 640;
const CHAT_WINDOW_MIN_WIDTH = 380;
const CHAT_WINDOW_MIN_HEIGHT = 520;
const PET_WINDOW_WIDTH = 250;
const PET_WINDOW_HEIGHT = 330;
const FOLLOW_STEP_MS = 16;
const FOLLOW_STIFFNESS = 0.2;
const ROAM_MIN_DELAY_MS = 2e4;
const ROAM_MAX_DELAY_MS = 3e4;
const ROAM_MIN_DURATION_MS = 900;
const ROAM_MAX_DURATION_MS = 1300;
const ROAM_RANGE_X = 36;
const ROAM_RANGE_Y = 82;
const ROAM_MIN_DISTANCE_Y = 42;
const CHAT_TRANSITION_MS = 230;
const CHAT_COLLAPSED_SIZE = 92;
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
let chatTransition = null;
const windowAnimationTimers = /* @__PURE__ */ new Map();
const windowOpacityTimers = /* @__PURE__ */ new Map();
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
function isRendererDevUrl(url) {
  if (!VITE_DEV_SERVER_URL) {
    return false;
  }
  try {
    return new URL(url).origin === new URL(VITE_DEV_SERVER_URL).origin;
  } catch {
    return false;
  }
}
function openExternalUrl(url) {
  if (!/^https?:\/\//i.test(url) || isRendererDevUrl(url)) {
    return false;
  }
  void shell.openExternal(url);
  return true;
}
function registerExternalLinkHandling(window) {
  window.webContents.setWindowOpenHandler(({ url }) => {
    if (openExternalUrl(url)) {
      return { action: "deny" };
    }
    return { action: "allow" };
  });
  window.webContents.on("will-navigate", (event, url) => {
    if (openExternalUrl(url)) {
      event.preventDefault();
    }
  });
}
function createPetWindow() {
  const workArea = screen.getPrimaryDisplay().workArea;
  petWindow = new BrowserWindow({
    width: PET_WINDOW_WIDTH,
    height: PET_WINDOW_HEIGHT,
    x: workArea.x + workArea.width - PET_WINDOW_WIDTH - 48,
    y: workArea.y + workArea.height - PET_WINDOW_HEIGHT - 48,
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
  petWindow.setIgnoreMouseEvents(true, { forward: true });
  petWindow.once("ready-to-show", () => {
    petWindow == null ? void 0 : petWindow.show();
    schedulePetRoam();
  });
  petWindow.webContents.on("did-finish-load", sendCurrentPetState);
  petWindow.on("move", syncChatWindowToPetWindow);
  petWindow.on("closed", () => {
    stopPetFollow();
    cancelPetRoam();
    cancelWindowOpacityAnimation(petWindow);
    clearMoveSyncSuppressions();
    petWindow = null;
  });
  loadRenderer(petWindow, "pet");
}
function createChatWindow() {
  chatWindow = new BrowserWindow({
    width: CHAT_WINDOW_WIDTH,
    height: CHAT_WINDOW_HEIGHT,
    minWidth: CHAT_WINDOW_MIN_WIDTH,
    minHeight: CHAT_WINDOW_MIN_HEIGHT,
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
  registerExternalLinkHandling(chatWindow);
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
    chatTransition = null;
    if (chatWindow) {
      cancelWindowAnimation(chatWindow);
      cancelWindowOpacityAnimation(chatWindow);
    }
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
function cancelWindowOpacityAnimation(window) {
  const timer = windowOpacityTimers.get(window.id);
  if (timer) {
    clearTimeout(timer);
    windowOpacityTimers.delete(window.id);
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
function setWindowBounds(window, bounds) {
  suppressMoveSyncFor(window);
  window.setBounds({
    x: Math.round(bounds.x),
    y: Math.round(bounds.y),
    width: Math.round(bounds.width),
    height: Math.round(bounds.height)
  });
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
function animateWindowBounds(window, target, duration = 180, onComplete) {
  cancelWindowAnimation(window);
  if (window.isDestroyed()) {
    return;
  }
  const start = window.getBounds();
  const startedAt = Date.now();
  const tick = () => {
    if (window.isDestroyed()) {
      windowAnimationTimers.delete(window.id);
      return;
    }
    const progress = clamp((Date.now() - startedAt) / duration, 0, 1);
    const eased = easeOutCubic(progress);
    setWindowBounds(window, {
      x: start.x + (target.x - start.x) * eased,
      y: start.y + (target.y - start.y) * eased,
      width: start.width + (target.width - start.width) * eased,
      height: start.height + (target.height - start.height) * eased
    });
    if (progress >= 1) {
      windowAnimationTimers.delete(window.id);
      onComplete == null ? void 0 : onComplete();
      return;
    }
    windowAnimationTimers.set(window.id, setTimeout(tick, 16));
  };
  tick();
}
function animateWindowPath(window, points, onComplete) {
  cancelWindowAnimation(window);
  if (window.isDestroyed() || points.length === 0) {
    onComplete == null ? void 0 : onComplete();
    return;
  }
  let index = 0;
  const runSegment = () => {
    if (window.isDestroyed()) {
      windowAnimationTimers.delete(window.id);
      return;
    }
    const point = points[index];
    const start = window.getBounds();
    const startedAt = Date.now();
    const tick = () => {
      if (window.isDestroyed()) {
        windowAnimationTimers.delete(window.id);
        return;
      }
      const progress = clamp((Date.now() - startedAt) / point.duration, 0, 1);
      const eased = easeOutCubic(progress);
      setWindowPosition(window, start.x + (point.x - start.x) * eased, start.y + (point.y - start.y) * eased);
      if (progress >= 1) {
        index += 1;
        if (index >= points.length) {
          windowAnimationTimers.delete(window.id);
          onComplete == null ? void 0 : onComplete();
          return;
        }
        runSegment();
        return;
      }
      windowAnimationTimers.set(window.id, setTimeout(tick, 16));
    };
    tick();
  };
  runSegment();
}
function animateWindowOpacity(window, targetOpacity, duration = 180, onComplete) {
  cancelWindowOpacityAnimation(window);
  if (window.isDestroyed()) {
    return;
  }
  const startOpacity = window.getOpacity();
  const startedAt = Date.now();
  const tick = () => {
    if (window.isDestroyed()) {
      windowOpacityTimers.delete(window.id);
      return;
    }
    const progress = clamp((Date.now() - startedAt) / duration, 0, 1);
    const eased = easeOutCubic(progress);
    const nextOpacity = startOpacity + (targetOpacity - startOpacity) * eased;
    window.setOpacity(nextOpacity);
    if (progress >= 1) {
      windowOpacityTimers.delete(window.id);
      return;
    }
    windowOpacityTimers.set(window.id, setTimeout(tick, 16));
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
function clearPetRoamTimer() {
  if (petRoamTimer) {
    clearTimeout(petRoamTimer);
    petRoamTimer = null;
  }
}
function cancelPetRoam() {
  clearPetRoamTimer();
  if (petWindow) {
    cancelWindowAnimation(petWindow);
  }
}
function schedulePetRoam(delay) {
  clearPetRoamTimer();
  if (!petWindow || petWindow.isDestroyed() || (chatWindow == null ? void 0 : chatWindow.isVisible()) || petPointerActive) {
    return;
  }
  const cooldownDelay = Math.max(0, petManualControlUntil - Date.now());
  const roamDelay = Math.max(0, randomBetween(ROAM_MIN_DELAY_MS, ROAM_MAX_DELAY_MS));
  petRoamTimer = setTimeout(startPetRoam, cooldownDelay + roamDelay);
}
function getPetMotionTarget(rangeX, rangeY) {
  if (!petWindow) {
    return null;
  }
  const petBounds = petWindow.getBounds();
  const workArea = screen.getDisplayMatching(petBounds).workArea;
  return {
    x: Math.round(
      clamp(
        petBounds.x + randomBetween(-rangeX, rangeX),
        workArea.x + WINDOW_GAP,
        workArea.x + workArea.width - petBounds.width - WINDOW_GAP
      )
    ),
    y: Math.round(
      clamp(
        petBounds.y + randomBetween(-rangeY, rangeY),
        workArea.y + WINDOW_GAP,
        workArea.y + workArea.height - petBounds.height - WINDOW_GAP
      )
    )
  };
}
function getPetRoamTarget() {
  if (!petWindow) {
    return null;
  }
  const petBounds = petWindow.getBounds();
  const workArea = screen.getDisplayMatching(petBounds).workArea;
  const minX = workArea.x + WINDOW_GAP;
  const maxX = workArea.x + workArea.width - petBounds.width - WINDOW_GAP;
  const minY = workArea.y + WINDOW_GAP;
  const maxY = workArea.y + workArea.height - petBounds.height - WINDOW_GAP;
  const canMoveDown = petBounds.y + ROAM_MIN_DISTANCE_Y <= maxY;
  const canMoveUp = petBounds.y - ROAM_MIN_DISTANCE_Y >= minY;
  let directionY = Math.random() > 0.5 ? 1 : -1;
  if (!canMoveDown && canMoveUp) {
    directionY = -1;
  } else if (!canMoveUp && canMoveDown) {
    directionY = 1;
  }
  const distanceY = randomBetween(ROAM_MIN_DISTANCE_Y, ROAM_RANGE_Y);
  let targetY = clamp(petBounds.y + directionY * distanceY, minY, maxY);
  if (Math.abs(targetY - petBounds.y) < ROAM_MIN_DISTANCE_Y && (canMoveDown || canMoveUp)) {
    targetY = clamp(petBounds.y - directionY * distanceY, minY, maxY);
  }
  const directionX = Math.random() > 0.5 ? 1 : -1;
  const distanceX = randomBetween(12, ROAM_RANGE_X);
  let targetX = clamp(petBounds.x + directionX * distanceX, minX, maxX);
  if (Math.abs(targetX - petBounds.x) < 8) {
    targetX = clamp(petBounds.x - directionX * distanceX, minX, maxX);
  }
  return {
    x: Math.round(targetX),
    y: Math.round(targetY)
  };
}
function performPetMotion(payload) {
  const force = Boolean(payload == null ? void 0 : payload.force);
  if (!petWindow || !force && !canPetRoam()) {
    return;
  }
  cancelPetRoam();
  if (force) {
    stopPetFollow();
  }
  const type = (payload == null ? void 0 : payload.type) || "vertical-run";
  const start = petWindow.getBounds();
  const workArea = screen.getDisplayMatching(start).workArea;
  const finish = (state = "idle", delay = 900) => {
    if (!force && !canPetRoam()) {
      return;
    }
    setPetState(state);
    setTimeout(() => {
      if (!petWindow || petWindow.isDestroyed() || (chatWindow == null ? void 0 : chatWindow.isVisible()) || petPointerActive) {
        return;
      }
      setPetState("idle");
      schedulePetRoam();
    }, delay);
  };
  if (type === "jump") {
    setPetState("happy");
    const jumpHeight = clamp(Number((payload == null ? void 0 : payload.rangeY) ?? 112), 60, 150);
    const peakY = clamp(start.y - jumpHeight, workArea.y + WINDOW_GAP, workArea.y + workArea.height - start.height - WINDOW_GAP);
    animateWindowPath(
      petWindow,
      [
        { x: start.x, y: peakY, duration: 320 },
        { x: start.x, y: start.y, duration: 460 }
      ],
      () => finish("happy", 700)
    );
    return;
  }
  if (type === "dash") {
    setPetState("running");
    const direction = start.x + start.width / 2 < workArea.x + workArea.width / 2 ? 1 : -1;
    const dashDistance = clamp(Number((payload == null ? void 0 : payload.rangeX) ?? 140), 80, 180);
    const targetX = clamp(
      start.x + direction * dashDistance,
      workArea.x + WINDOW_GAP,
      workArea.x + workArea.width - start.width - WINDOW_GAP
    );
    const targetY = clamp(
      start.y + randomBetween(-28, 28),
      workArea.y + WINDOW_GAP,
      workArea.y + workArea.height - start.height - WINDOW_GAP
    );
    animateWindowPath(
      petWindow,
      [
        { x: start.x + direction * 18, y: start.y - 14, duration: 140 },
        { x: targetX, y: targetY, duration: 640 },
        { x: targetX - direction * 18, y: targetY + 8, duration: 220 }
      ],
      () => finish("happy", 700)
    );
    return;
  }
  const rangeX = clamp(Number((payload == null ? void 0 : payload.rangeX) ?? ROAM_RANGE_X), 0, 120);
  const rangeY = clamp(Number((payload == null ? void 0 : payload.rangeY) ?? ROAM_RANGE_Y), 0, 180);
  const duration = Math.round(clamp(Number((payload == null ? void 0 : payload.duration) ?? 1050), 360, 2200));
  const target = getPetMotionTarget(rangeX, rangeY);
  if (!target) {
    return;
  }
  setPetState("running");
  animateWindowTo(petWindow, target.x, target.y, duration, () => finish("happy", 900));
}
function performPetRoamMotion() {
  if (!petWindow || !canPetRoam()) {
    return;
  }
  cancelPetRoam();
  const start = petWindow.getBounds();
  const target = getPetRoamTarget();
  if (!target) {
    return;
  }
  const duration = Math.round(randomBetween(ROAM_MIN_DURATION_MS, ROAM_MAX_DURATION_MS));
  const liftY = target.y < start.y ? -14 : 10;
  const midPoint = {
    x: Math.round(start.x + (target.x - start.x) * 0.42),
    y: Math.round(start.y + (target.y - start.y) * 0.42 + liftY)
  };
  setPetState("running");
  animateWindowPath(
    petWindow,
    [
      { x: midPoint.x, y: midPoint.y, duration: Math.round(duration * 0.38) },
      { x: target.x, y: target.y, duration: Math.round(duration * 0.62) }
    ],
    () => {
      if (!petWindow || petWindow.isDestroyed() || (chatWindow == null ? void 0 : chatWindow.isVisible()) || petPointerActive) {
        return;
      }
      setPetState("happy");
      setTimeout(() => {
        if (!petWindow || petWindow.isDestroyed() || (chatWindow == null ? void 0 : chatWindow.isVisible()) || petPointerActive) {
          return;
        }
        setPetState("idle");
        schedulePetRoam();
      }, 760);
    }
  );
}
function startPetRoam() {
  petRoamTimer = null;
  if (!canPetRoam() || !petWindow) {
    schedulePetRoam();
    return;
  }
  performPetRoamMotion();
}
function getChatWindowPosition() {
  if (!petWindow || !chatWindow) {
    return null;
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
  return {
    x: clamp(x, workArea.x + WINDOW_GAP, workArea.x + workArea.width - chatBounds.width - WINDOW_GAP),
    y
  };
}
function getChatWindowTargetBounds() {
  if (!chatWindow) {
    return null;
  }
  const position = getChatWindowPosition();
  if (!position) {
    return null;
  }
  const bounds = chatWindow.getBounds();
  const width = Math.max(bounds.width, CHAT_WINDOW_MIN_WIDTH);
  const height = Math.max(bounds.height, CHAT_WINDOW_MIN_HEIGHT);
  return {
    x: position.x,
    y: position.y,
    width,
    height
  };
}
function getChatCollapsedBounds() {
  if (!petWindow || !chatWindow) {
    return null;
  }
  const petBounds = petWindow.getBounds();
  const workArea = screen.getDisplayMatching(petBounds).workArea;
  const size = CHAT_COLLAPSED_SIZE;
  return {
    x: Math.round(
      clamp(
        petBounds.x + petBounds.width / 2 - size / 2,
        workArea.x + WINDOW_GAP,
        workArea.x + workArea.width - size - WINDOW_GAP
      )
    ),
    y: Math.round(
      clamp(
        petBounds.y + petBounds.height / 2 - size / 2,
        workArea.y + WINDOW_GAP,
        workArea.y + workArea.height - size - WINDOW_GAP
      )
    ),
    width: size,
    height: size
  };
}
function positionChatWindow(options = {}) {
  if (!chatWindow) {
    return;
  }
  const position = getChatWindowPosition();
  if (!position) {
    return;
  }
  moveWindowTo(chatWindow, position.x, position.y, Boolean(options.animated));
}
function showChatWindowAnimated() {
  if (!chatWindow) {
    return;
  }
  if (chatWindow.isVisible() && chatTransition !== "hiding") {
    chatWindow.focus();
    return;
  }
  if (chatTransition === "showing") {
    return;
  }
  const target = getChatWindowTargetBounds();
  const collapsed = getChatCollapsedBounds();
  if (!target || !collapsed) {
    return;
  }
  cancelWindowAnimation(chatWindow);
  cancelWindowOpacityAnimation(chatWindow);
  chatTransition = "showing";
  chatWindow.setMinimumSize(1, 1);
  if (!chatWindow.isVisible()) {
    setWindowBounds(chatWindow, collapsed);
    chatWindow.setOpacity(0.72);
    chatWindow.show();
  }
  chatWindow.focus();
  animateWindowBounds(chatWindow, target, CHAT_TRANSITION_MS, () => {
    if (!chatWindow || chatWindow.isDestroyed()) {
      return;
    }
    chatWindow.setMinimumSize(CHAT_WINDOW_MIN_WIDTH, CHAT_WINDOW_MIN_HEIGHT);
    chatTransition = null;
  });
  animateWindowOpacity(chatWindow, 1, CHAT_TRANSITION_MS);
}
function hideChatWindowAnimated() {
  if (!chatWindow || !chatWindow.isVisible()) {
    return;
  }
  if (chatTransition === "hiding") {
    return;
  }
  const start = chatWindow.getBounds();
  const collapsed = getChatCollapsedBounds();
  if (!collapsed) {
    chatWindow.hide();
    return;
  }
  cancelWindowAnimation(chatWindow);
  cancelWindowOpacityAnimation(chatWindow);
  chatTransition = "hiding";
  chatWindow.setMinimumSize(1, 1);
  animateWindowBounds(chatWindow, collapsed, CHAT_TRANSITION_MS, () => {
    if (!chatWindow || chatWindow.isDestroyed()) {
      return;
    }
    chatWindow.hide();
    setWindowBounds(chatWindow, {
      x: start.x,
      y: start.y,
      width: Math.max(start.width, CHAT_WINDOW_MIN_WIDTH),
      height: Math.max(start.height, CHAT_WINDOW_MIN_HEIGHT)
    });
    chatWindow.setMinimumSize(CHAT_WINDOW_MIN_WIDTH, CHAT_WINDOW_MIN_HEIGHT);
    chatWindow.setOpacity(1);
    chatTransition = null;
  });
  animateWindowOpacity(chatWindow, 0.18, CHAT_TRANSITION_MS);
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
  petWindow == null ? void 0 : petWindow.setIgnoreMouseEvents(!petPointerActive, { forward: true });
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
function showChatWindow() {
  if (!chatWindow) {
    createChatWindow();
  }
  if (!chatWindow) {
    return;
  }
  cancelPetRoam();
  stopPetFollow();
  setPetState("idle");
  showChatWindowAnimated();
}
function toggleChatWindow() {
  if (!chatWindow) {
    createChatWindow();
  }
  if (!chatWindow) {
    return;
  }
  if (chatTransition === "showing") {
    return;
  }
  if (chatTransition === "hiding") {
    showChatWindow();
    return;
  }
  if (chatWindow.isVisible()) {
    setPetState("idle");
    hideChatWindowAnimated();
    return;
  }
  showChatWindow();
}
function sendCurrentPetState() {
  if (!petWindow || petWindow.isDestroyed()) {
    return;
  }
  petWindow.webContents.send("assistant:pet-state", currentPetState);
}
function sendPetAction(action) {
  if (!petWindow || petWindow.isDestroyed()) {
    return;
  }
  petWindow.webContents.send("assistant:pet-action", action);
}
function horizontalDirectionFromWindow(bounds, workArea) {
  return bounds.x + bounds.width / 2 < workArea.x + workArea.width / 2 ? 1 : -1;
}
function resolveHorizontalTarget(start, workArea, distance) {
  const minX = workArea.x + WINDOW_GAP;
  const maxX = workArea.x + workArea.width - start.width - WINDOW_GAP;
  let direction = horizontalDirectionFromWindow(start, workArea);
  let targetX = clamp(start.x + direction * distance, minX, maxX);
  if (Math.abs(targetX - start.x) < Math.min(52, distance * 0.48)) {
    direction *= -1;
    targetX = clamp(start.x + direction * distance, minX, maxX);
  }
  return { direction, targetX };
}
function performPetInteractionMotion(action) {
  if (!petWindow || petWindow.isDestroyed()) {
    return;
  }
  stopPetFollow();
  cancelPetRoam();
  petManualControlUntil = Date.now() + 1800;
  const start = petWindow.getBounds();
  const workArea = screen.getDisplayMatching(start).workArea;
  if (action === "jump") {
    setPetState("happy");
    const { direction: direction2, targetX: targetX2 } = resolveHorizontalTarget(start, workArea, 78);
    const targetY2 = clamp(
      start.y + randomBetween(-18, 18),
      workArea.y + WINDOW_GAP,
      workArea.y + workArea.height - start.height - WINDOW_GAP
    );
    const peakY = clamp(start.y - 118, workArea.y + WINDOW_GAP, workArea.y + workArea.height - start.height - WINDOW_GAP);
    animateWindowPath(
      petWindow,
      [
        { x: start.x + direction2 * 28, y: peakY, duration: 260 },
        { x: targetX2, y: targetY2, duration: 430 },
        { x: targetX2 - direction2 * 6, y: targetY2 + 4, duration: 120 }
      ],
      () => {
        setPetState("happy");
        setTimeout(() => {
          if (!(chatWindow == null ? void 0 : chatWindow.isVisible())) {
            setPetState("idle");
            schedulePetRoam();
          }
        }, 700);
      }
    );
    return;
  }
  setPetState("running");
  const { direction, targetX } = resolveHorizontalTarget(start, workArea, 178);
  const targetY = clamp(
    start.y + randomBetween(-34, 34),
    workArea.y + WINDOW_GAP,
    workArea.y + workArea.height - start.height - WINDOW_GAP
  );
  animateWindowPath(
    petWindow,
    [
      { x: start.x + direction * 24, y: start.y - 12, duration: 120 },
      { x: targetX, y: targetY, duration: 560 },
      { x: targetX - direction * 12, y: targetY + 6, duration: 180 }
    ],
    () => {
      setPetState("happy");
      setTimeout(() => {
        if (!(chatWindow == null ? void 0 : chatWindow.isVisible())) {
          setPetState("idle");
          schedulePetRoam();
        }
      }, 700);
    }
  );
}
function runPetMenuAction(action) {
  sendPetAction(action);
  if (action === "jump") {
    setPetPointerActive(false);
    setTimeout(() => performPetInteractionMotion("jump"), 30);
    return;
  }
  if (action === "run") {
    setPetPointerActive(false);
    setTimeout(() => performPetInteractionMotion("run"), 30);
    return;
  }
  if (action === "idle") {
    setPetState("idle");
  } else if (action === "sleep") {
    setPetState("sleepy");
  }
  if (!(chatWindow == null ? void 0 : chatWindow.isVisible())) {
    schedulePetRoam();
  }
}
function setPetState(state) {
  if (state !== "idle" && state !== "thinking" && state !== "speaking" && state !== "happy" && state !== "curious" && state !== "sleepy" && state !== "running" && state !== "error") {
    return;
  }
  currentPetState = state;
  sendCurrentPetState();
}
function showPetMenu() {
  if (!petWindow || petWindow.isDestroyed()) {
    return;
  }
  const menu = Menu.buildFromTemplate([
    { label: "打招呼 Wave", click: () => runPetMenuAction("wave") },
    { label: "开心跳 Jump", click: () => runPetMenuAction("jump") },
    { label: "放烟花 Fireworks", click: () => runPetMenuAction("fireworks") },
    { label: "小跑一下 Run", click: () => runPetMenuAction("run") },
    { type: "separator" },
    { label: "睡觉 Sleep", click: () => runPetMenuAction("sleep") },
    { label: "恢复空闲 Idle", click: () => runPetMenuAction("idle") }
  ]);
  menu.popup({
    window: petWindow,
    callback: () => {
      setPetPointerActive(false);
    }
  });
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
  ipcMain.handle("assistant:show-chat", () => showChatWindow());
  ipcMain.handle("assistant:hide-chat", () => {
    setPetState("idle");
    hideChatWindowAnimated();
  });
  ipcMain.handle("assistant:get-backend-url", () => BACKEND_URL);
  ipcMain.handle("assistant:set-pet-state", (_event, state) => setPetState(state));
  ipcMain.handle("assistant:set-pet-pointer-active", (_event, active) => setPetPointerActive(active));
  ipcMain.handle("assistant:show-pet-menu", () => showPetMenu());
  ipcMain.handle(
    "assistant:perform-pet-motion",
    (_event, payload) => performPetMotion(payload)
  );
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
