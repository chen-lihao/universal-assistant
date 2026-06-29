"use strict";
const electron = require("electron");
electron.contextBridge.exposeInMainWorld("assistant", {
  toggleChat: () => electron.ipcRenderer.invoke("assistant:toggle-chat"),
  hideChat: () => electron.ipcRenderer.invoke("assistant:hide-chat"),
  getBackendUrl: () => electron.ipcRenderer.invoke("assistant:get-backend-url"),
  setPetState: (state) => electron.ipcRenderer.invoke("assistant:set-pet-state", state),
  setPetPointerActive: (active) => electron.ipcRenderer.invoke("assistant:set-pet-pointer-active", active),
  showPetMenu: () => electron.ipcRenderer.invoke("assistant:show-pet-menu"),
  performPetMotion: (payload) => electron.ipcRenderer.invoke("assistant:perform-pet-motion", payload),
  movePetBy: (payload) => electron.ipcRenderer.invoke("assistant:move-pet-by", payload),
  onPetState: (callback) => {
    const listener = (_event, state) => callback(state);
    electron.ipcRenderer.on("assistant:pet-state", listener);
    return () => electron.ipcRenderer.removeListener("assistant:pet-state", listener);
  },
  onPetAction: (callback) => {
    const listener = (_event, action) => callback(action);
    electron.ipcRenderer.on("assistant:pet-action", listener);
    return () => electron.ipcRenderer.removeListener("assistant:pet-action", listener);
  },
  files: {
    select: (options) => electron.ipcRenderer.invoke("file:select", options),
    readText: (filePath) => electron.ipcRenderer.invoke("file:read-text", filePath),
    writeText: (payload) => electron.ipcRenderer.invoke("file:write-text", payload),
    saveTextAs: (payload) => electron.ipcRenderer.invoke("file:save-text-as", payload),
    convertText: (payload) => electron.ipcRenderer.invoke("file:convert-text", payload)
  }
});
