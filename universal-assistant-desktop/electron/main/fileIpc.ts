import { dialog, ipcMain } from 'electron'
import { access, readFile, stat, writeFile } from 'node:fs/promises'
import { constants } from 'node:fs'
import path from 'node:path'

const MAX_TEXT_FILE_SIZE = 5 * 1024 * 1024
const TEXT_EXTENSIONS = new Set([
  '.txt',
  '.md',
  '.markdown',
  '.json',
  '.csv',
  '.tsv',
  '.xml',
  '.html',
  '.css',
  '.js',
  '.ts',
  '.vue',
  '.java',
  '.properties',
  '.yml',
  '.yaml',
])

const grantedFiles = new Set<string>()
const grantedDirectories = new Set<string>()

async function normalizePath(filePath: string) {
  return path.resolve(filePath)
}

function isPathGranted(filePath: string) {
  if (grantedFiles.has(filePath)) {
    return true
  }

  for (const directory of grantedDirectories) {
    if (filePath === directory || filePath.startsWith(`${directory}${path.sep}`)) {
      return true
    }
  }

  return false
}

async function assertReadableTextFile(filePath: string) {
  const normalized = await normalizePath(filePath)
  if (!isPathGranted(normalized)) {
    throw new Error('File access was not granted from the picker.')
  }

  const info = await stat(normalized)
  if (!info.isFile()) {
    throw new Error('Only files can be read.')
  }

  if (info.size > MAX_TEXT_FILE_SIZE) {
    throw new Error('Text files larger than 5 MB are not supported in this MVP.')
  }

  const extension = path.extname(normalized).toLowerCase()
  if (!TEXT_EXTENSIONS.has(extension)) {
    throw new Error(`Unsupported text file type: ${extension || 'unknown'}`)
  }

  return normalized
}

async function assertWritableTextFile(filePath: string) {
  const normalized = await normalizePath(filePath)
  if (!isPathGranted(normalized)) {
    throw new Error('File access was not granted from the picker.')
  }

  const extension = path.extname(normalized).toLowerCase()
  if (!TEXT_EXTENSIONS.has(extension)) {
    throw new Error(`Unsupported text file type: ${extension || 'unknown'}`)
  }

  await access(path.dirname(normalized), constants.W_OK)
  return normalized
}

function convertText(content: string, targetFormat: string) {
  switch (targetFormat) {
    case 'txt':
      return content
    case 'md':
      return content.startsWith('# ') ? content : `# Converted Document\n\n${content}`
    case 'json':
      try {
        return JSON.stringify(JSON.parse(content), null, 2)
      } catch {
        return JSON.stringify({ content }, null, 2)
      }
    case 'csv': {
      return content
        .split(/\r?\n/)
        .filter(Boolean)
        .map((line) => `"${line.replace(/"/g, '""')}"`)
        .join('\n')
    }
    default:
      throw new Error(`Unsupported target format: ${targetFormat}`)
  }
}

export function registerFileIpcHandlers() {
  ipcMain.handle('file:select', async (_event, options?: { directory?: boolean; multiple?: boolean }) => {
    const properties: Array<'openFile' | 'openDirectory' | 'multiSelections'> = [
      options?.directory ? 'openDirectory' : 'openFile',
    ]

    if (options?.multiple) {
      properties.push('multiSelections')
    }

    const result = await dialog.showOpenDialog({
      title: options?.directory ? 'Select a folder' : 'Select a text file',
      properties,
      filters: options?.directory
        ? undefined
        : [
            {
              name: 'Text files',
              extensions: [...TEXT_EXTENSIONS].map((extension) => extension.slice(1)),
            },
          ],
    })

    if (result.canceled) {
      return { canceled: true, paths: [] }
    }

    const normalizedPaths = await Promise.all(result.filePaths.map((filePath) => normalizePath(filePath)))
    for (const selectedPath of normalizedPaths) {
      if (options?.directory) {
        grantedDirectories.add(selectedPath)
      } else {
        grantedFiles.add(selectedPath)
      }
    }

    return { canceled: false, paths: normalizedPaths }
  })

  ipcMain.handle('file:read-text', async (_event, filePath: string) => {
    const normalized = await assertReadableTextFile(filePath)
    const content = await readFile(normalized, 'utf8')
    const info = await stat(normalized)

    return {
      path: normalized,
      name: path.basename(normalized),
      size: info.size,
      content,
    }
  })

  ipcMain.handle('file:write-text', async (_event, payload: { filePath: string; content: string }) => {
    const normalized = await assertWritableTextFile(payload.filePath)
    await writeFile(normalized, payload.content, 'utf8')
    return { path: normalized, name: path.basename(normalized) }
  })

  ipcMain.handle('file:save-text-as', async (_event, payload: { defaultPath?: string; content: string }) => {
    const result = await dialog.showSaveDialog({
      title: 'Save text file',
      defaultPath: payload.defaultPath,
      filters: [{ name: 'Text files', extensions: [...TEXT_EXTENSIONS].map((extension) => extension.slice(1)) }],
    })

    if (result.canceled || !result.filePath) {
      return { canceled: true }
    }

    const normalized = await normalizePath(result.filePath)
    await access(path.dirname(normalized), constants.W_OK)
    await writeFile(normalized, payload.content, 'utf8')
    grantedFiles.add(normalized)

    return { canceled: false, path: normalized, name: path.basename(normalized) }
  })

  ipcMain.handle('file:convert-text', async (_event, payload: { filePath: string; targetFormat: string }) => {
    const normalized = await assertReadableTextFile(payload.filePath)
    const content = await readFile(normalized, 'utf8')
    const converted = convertText(content, payload.targetFormat)
    const outputPath = normalized.replace(/\.[^/.]+$/, `.${payload.targetFormat}`)

    return {
      sourcePath: normalized,
      defaultPath: outputPath === normalized ? `${normalized}.${payload.targetFormat}` : outputPath,
      content: converted,
      targetFormat: payload.targetFormat,
    }
  })
}
