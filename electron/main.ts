import {
  app,
  BrowserWindow,
  Menu,
  Notification,
  Tray,
  ipcMain,
  nativeImage,
  nativeTheme,
  type NativeImage,
} from 'electron'
import fs from 'node:fs'
import path from 'node:path'
import {
  closeDb,
  countTodayOpenTasks,
  createPlan,
  createTask,
  deletePlan,
  deleteTask,
  initDb,
  listAllTasks,
  listDueReminders,
  listPlans,
  listTasks,
  updatePlan,
  updateTask,
} from './db'
import { EdgeDockManager } from './edgeDock'
import type {
  CreatePlanInput,
  CreateTaskInput,
  FontFamilyId,
  FontSizeId,
  PlanFilterId,
  ThemeId,
  UpdatePlanInput,
  UpdateTaskInput,
} from './types'

let mainWindow: BrowserWindow | null = null
let tray: Tray | null = null
let reminderTimer: NodeJS.Timeout | null = null
let isQuitting = false
let alwaysOnTop = false
let theme: ThemeId = 'white'
let planFilter: PlanFilterId = 'all'
let showCompleted = true
let fontSize: FontSizeId = 'medium'
let fontFamily: FontFamilyId = 'yahei'

const edgeDock = new EdgeDockManager((state) => {
  mainWindow?.webContents.send('ui:edge-dock', state.enabled)
  updateTrayMenu()
})

const THEME_BG: Record<ThemeId, string> = {
  white: '#f4f6f6',
  black: '#121417',
  colorful: '#fff7ed',
}

const isDev = !app.isPackaged

function createTrayIcon(): NativeImage {
  const candidates = [
    path.join(__dirname, '../build/tray.png'),
    path.join(process.resourcesPath, 'build/tray.png'),
    path.join(__dirname, '../build/icon.png'),
  ]
  for (const file of candidates) {
    if (fs.existsSync(file)) {
      return nativeImage.createFromPath(file)
    }
  }
  return nativeImage.createEmpty()
}

function getPreloadPath() {
  return path.join(__dirname, 'preload.js')
}

function applyChromeTheme(next: ThemeId) {
  theme = next
  nativeTheme.themeSource = next === 'black' ? 'dark' : 'light'
  mainWindow?.setBackgroundColor(THEME_BG[next])
}

function currentSettings() {
  return {
    openAtLogin: app.getLoginItemSettings().openAtLogin,
    alwaysOnTop: alwaysOnTop || edgeDock.isEnabled(),
    edgeDock: edgeDock.isEnabled(),
    theme,
    planFilter,
    showCompleted,
    fontSize,
    fontFamily,
  }
}

function setAlwaysOnTopState(enabled: boolean) {
  // 侧边停靠依赖置顶；关闭置顶时同步关闭侧边停靠
  if (!enabled && edgeDock.isEnabled()) {
    edgeDock.setEnabled(false)
  }
  alwaysOnTop = enabled
  mainWindow?.setAlwaysOnTop(alwaysOnTop || edgeDock.isEnabled())
  mainWindow?.webContents.send('ui:always-on-top', alwaysOnTop || edgeDock.isEnabled())
  updateTrayMenu()
}

function setEdgeDockState(enabled: boolean) {
  if (enabled) {
    alwaysOnTop = true
    mainWindow?.setAlwaysOnTop(true)
    mainWindow?.setSkipTaskbar(true)
    mainWindow?.webContents.send('ui:always-on-top', true)
  }
  edgeDock.setEnabled(enabled)
  if (!enabled) {
    mainWindow?.setAlwaysOnTop(alwaysOnTop)
    mainWindow?.setSkipTaskbar(false)
    mainWindow?.webContents.send('ui:always-on-top', alwaysOnTop)
  }
}

function createWindow() {
  Menu.setApplicationMenu(null)

  mainWindow = new BrowserWindow({
    width: 560,
    height: 520,
    minWidth: 280,
    minHeight: 240,
    show: false,
    frame: false,
    backgroundColor: THEME_BG[theme],
    title: 'TodoThings',
    alwaysOnTop,
    webPreferences: {
      preload: getPreloadPath(),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  })

  applyChromeTheme(theme)
  edgeDock.attach(mainWindow)

  mainWindow.on('ready-to-show', () => {
    mainWindow?.show()
  })

  mainWindow.on('close', (event) => {
    if (!isQuitting) {
      event.preventDefault()
      if (edgeDock.isEnabled()) {
        // 开启侧边停靠时：关闭主界面 → 缩到边缘白线，应用继续留在托盘
        edgeDock.collapseNow()
      } else {
        mainWindow?.hide()
      }
    }
  })

  mainWindow.on('maximize', () => {
    edgeDock.ensureExpanded()
    mainWindow?.webContents.send('ui:window-state', { maximized: true })
  })
  mainWindow.on('unmaximize', () => {
    mainWindow?.webContents.send('ui:window-state', { maximized: false })
  })

  mainWindow.on('closed', () => {
    edgeDock.detach()
    mainWindow = null
  })

  if (isDev && process.env.VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL)
  } else {
    mainWindow.loadFile(path.join(__dirname, '../dist/index.html'))
  }
}

function showMainWindow() {
  if (!mainWindow) {
    createWindow()
    return
  }
  edgeDock.ensureExpanded()
  if (mainWindow.isMinimized()) mainWindow.restore()
  mainWindow.show()
  mainWindow.focus()
}

function updateTrayMenu() {
  if (!tray) return
  const todayCount = countTodayOpenTasks()
  tray.setToolTip(todayCount > 0 ? `TodoThings · 今日 ${todayCount} 项` : 'TodoThings')

  const menu = Menu.buildFromTemplate([
    {
      label: '打开 TodoThings',
      click: () => showMainWindow(),
    },
    {
      label: '新建待办',
      click: () => {
        showMainWindow()
        mainWindow?.webContents.send('ui:focus-new-task')
      },
    },
    {
      label: '新建计划',
      click: () => {
        showMainWindow()
        mainWindow?.webContents.send('ui:create-plan')
      },
    },
    {
      label: '始终置顶',
      type: 'checkbox',
      checked: alwaysOnTop || edgeDock.isEnabled(),
      click: (item) => setAlwaysOnTopState(item.checked),
    },
    {
      label: '侧边停靠',
      type: 'checkbox',
      checked: edgeDock.isEnabled(),
      click: (item) => setEdgeDockState(item.checked),
    },
    { type: 'separator' },
    {
      label: '退出',
      click: () => {
        isQuitting = true
        app.quit()
      },
    },
  ])
  tray.setContextMenu(menu)
}

function createTray() {
  tray = new Tray(createTrayIcon())
  tray.on('double-click', () => showMainWindow())
  updateTrayMenu()
}

function fireReminder(taskId: string, title: string) {
  if (Notification.isSupported()) {
    const n = new Notification({
      title: '待办提醒',
      body: title,
      silent: false,
    })
    n.on('click', () => showMainWindow())
    n.show()
  }
  mainWindow?.webContents.send('ui:reminder', { taskId, title })
  updateTask({ id: taskId, remindedAt: new Date().toISOString() })
  updateTrayMenu()
}

function checkReminders() {
  try {
    const due = listDueReminders(Date.now())
    for (const task of due) {
      fireReminder(task.id, task.title)
    }
    updateTrayMenu()
  } catch (err) {
    console.error('reminder check failed', err)
  }
}

function registerIpc() {
  ipcMain.handle('plans:list', () => listPlans())
  ipcMain.handle('plans:create', (_e, input: CreatePlanInput) => {
    const plan = createPlan(input)
    planFilter = plan.id
    return plan
  })
  ipcMain.handle('plans:update', (_e, input: UpdatePlanInput) => updatePlan(input))
  ipcMain.handle('plans:delete', (_e, id: string) => {
    const ok = deletePlan(id)
    if (ok && planFilter === id) planFilter = 'all'
    return ok
  })

  ipcMain.handle('tasks:list', (_e, planId: string) => {
    if (planId === 'all') return listAllTasks()
    return listTasks(planId)
  })
  ipcMain.handle('tasks:listAll', () => listAllTasks())
  ipcMain.handle('tasks:create', (_e, input: CreateTaskInput) => {
    const task = createTask(input)
    updateTrayMenu()
    return task
  })
  ipcMain.handle('tasks:update', (_e, input: UpdateTaskInput) => {
    const task = updateTask(input)
    updateTrayMenu()
    return task
  })
  ipcMain.handle('tasks:delete', (_e, id: string) => {
    const ok = deleteTask(id)
    updateTrayMenu()
    return ok
  })

  ipcMain.handle('settings:get', () => currentSettings())
  ipcMain.handle('settings:setOpenAtLogin', (_e, enabled: boolean) => {
    app.setLoginItemSettings({ openAtLogin: enabled, openAsHidden: true })
    return currentSettings()
  })
  ipcMain.handle('settings:setAlwaysOnTop', (_e, enabled: boolean) => {
    setAlwaysOnTopState(enabled)
    return currentSettings()
  })
  ipcMain.handle('settings:setEdgeDock', (_e, enabled: boolean) => {
    setEdgeDockState(enabled)
    return currentSettings()
  })
  ipcMain.handle('settings:setTheme', (_e, next: ThemeId) => {
    applyChromeTheme(next)
    mainWindow?.webContents.send('ui:theme', next)
    return currentSettings()
  })
  ipcMain.handle('settings:setPlanFilter', (_e, next: PlanFilterId) => {
    planFilter = next
    return currentSettings()
  })
  ipcMain.handle('settings:setShowCompleted', (_e, enabled: boolean) => {
    showCompleted = enabled
    mainWindow?.webContents.send('ui:show-completed', enabled)
    return currentSettings()
  })
  ipcMain.handle('settings:setFontSize', (_e, next: FontSizeId) => {
    fontSize = next
    mainWindow?.webContents.send('ui:font-size', next)
    return currentSettings()
  })
  ipcMain.handle('settings:setFontFamily', (_e, next: FontFamilyId) => {
    fontFamily = next
    mainWindow?.webContents.send('ui:font-family', next)
    return currentSettings()
  })

  ipcMain.handle('window:minimize', () => {
    edgeDock.ensureExpanded()
    mainWindow?.minimize()
  })
  ipcMain.handle('window:maximizeToggle', () => {
    if (!mainWindow) return false
    edgeDock.ensureExpanded()
    if (mainWindow.isMaximized()) mainWindow.unmaximize()
    else mainWindow.maximize()
    return mainWindow.isMaximized()
  })
  ipcMain.handle('window:close', () => mainWindow?.close())
  ipcMain.handle('window:isMaximized', () => mainWindow?.isMaximized() ?? false)

  ipcMain.handle('app:showWindow', () => {
    showMainWindow()
  })
}

app.whenReady().then(async () => {
  await initDb()
  registerIpc()
  createWindow()
  createTray()
  checkReminders()
  reminderTimer = setInterval(checkReminders, 30_000)

  app.on('activate', () => {
    showMainWindow()
  })
})

app.on('before-quit', () => {
  isQuitting = true
  edgeDock.dispose()
  if (reminderTimer) clearInterval(reminderTimer)
  closeDb()
})

app.on('window-all-closed', () => {
  // Keep running in tray on Windows
})
