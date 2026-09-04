import { contextBridge, ipcRenderer } from 'electron'
import type {
  AppSettings,
  CreatePlanInput,
  CreateTaskInput,
  DockViewState,
  EdgeDockUiState,
  FontFamilyId,
  FontSizeId,
  Plan,
  PlanFilterId,
  Task,
  ThemeId,
  UpdatePlanInput,
  UpdateTaskInput,
} from './types'

const api = {
  listPlans: (): Promise<Plan[]> => ipcRenderer.invoke('plans:list'),
  createPlan: (input: CreatePlanInput): Promise<Plan> =>
    ipcRenderer.invoke('plans:create', input),
  updatePlan: (input: UpdatePlanInput): Promise<Plan | null> =>
    ipcRenderer.invoke('plans:update', input),
  deletePlan: (id: string): Promise<boolean> => ipcRenderer.invoke('plans:delete', id),

  listTasks: (planId: string): Promise<Task[]> => ipcRenderer.invoke('tasks:list', planId),
  listAllTasks: (): Promise<Task[]> => ipcRenderer.invoke('tasks:listAll'),
  createTask: (input: CreateTaskInput): Promise<Task> =>
    ipcRenderer.invoke('tasks:create', input),
  updateTask: (input: UpdateTaskInput): Promise<Task | null> =>
    ipcRenderer.invoke('tasks:update', input),
  deleteTask: (id: string): Promise<boolean> => ipcRenderer.invoke('tasks:delete', id),

  getSettings: (): Promise<AppSettings> => ipcRenderer.invoke('settings:get'),
  setOpenAtLogin: (enabled: boolean): Promise<AppSettings> =>
    ipcRenderer.invoke('settings:setOpenAtLogin', enabled),
  setAlwaysOnTop: (enabled: boolean): Promise<AppSettings> =>
    ipcRenderer.invoke('settings:setAlwaysOnTop', enabled),
  setEdgeDock: (enabled: boolean): Promise<AppSettings> =>
    ipcRenderer.invoke('settings:setEdgeDock', enabled),
  setTheme: (theme: ThemeId): Promise<AppSettings> =>
    ipcRenderer.invoke('settings:setTheme', theme),
  setPlanFilter: (planFilter: PlanFilterId): Promise<AppSettings> =>
    ipcRenderer.invoke('settings:setPlanFilter', planFilter),
  setShowCompleted: (enabled: boolean): Promise<AppSettings> =>
    ipcRenderer.invoke('settings:setShowCompleted', enabled),
  setFontSize: (fontSize: FontSizeId): Promise<AppSettings> =>
    ipcRenderer.invoke('settings:setFontSize', fontSize),
  setFontFamily: (fontFamily: FontFamilyId): Promise<AppSettings> =>
    ipcRenderer.invoke('settings:setFontFamily', fontFamily),

  windowMinimize: (): Promise<void> => ipcRenderer.invoke('window:minimize'),
  windowMaximizeToggle: (): Promise<boolean> => ipcRenderer.invoke('window:maximizeToggle'),
  windowClose: (): Promise<void> => ipcRenderer.invoke('window:close'),
  windowIsMaximized: (): Promise<boolean> => ipcRenderer.invoke('window:isMaximized'),

  showWindow: (): Promise<void> => ipcRenderer.invoke('app:showWindow'),
  onFocusNewTask: (cb: () => void) => {
    const handler = () => cb()
    ipcRenderer.on('ui:focus-new-task', handler)
    return () => ipcRenderer.removeListener('ui:focus-new-task', handler)
  },
  onCreatePlan: (cb: () => void) => {
    const handler = () => cb()
    ipcRenderer.on('ui:create-plan', handler)
    return () => ipcRenderer.removeListener('ui:create-plan', handler)
  },
  onTheme: (cb: (theme: ThemeId) => void) => {
    const handler = (_: Electron.IpcRendererEvent, next: ThemeId) => cb(next)
    ipcRenderer.on('ui:theme', handler)
    return () => ipcRenderer.removeListener('ui:theme', handler)
  },
  onAlwaysOnTop: (cb: (enabled: boolean) => void) => {
    const handler = (_: Electron.IpcRendererEvent, enabled: boolean) => cb(enabled)
    ipcRenderer.on('ui:always-on-top', handler)
    return () => ipcRenderer.removeListener('ui:always-on-top', handler)
  },
  onEdgeDock: (cb: (state: EdgeDockUiState) => void) => {
    const handler = (_: Electron.IpcRendererEvent, state: EdgeDockUiState) => cb(state)
    ipcRenderer.on('ui:edge-dock', handler)
    return () => ipcRenderer.removeListener('ui:edge-dock', handler)
  },
  onPlanFilter: (cb: (planFilter: PlanFilterId) => void) => {
    const handler = (_: Electron.IpcRendererEvent, next: PlanFilterId) => cb(next)
    ipcRenderer.on('ui:plan-filter', handler)
    return () => ipcRenderer.removeListener('ui:plan-filter', handler)
  },
  dockReady: (): Promise<void> => ipcRenderer.invoke('dock:ready'),
  dockPointer: (inside: boolean): Promise<void> => ipcRenderer.invoke('dock:pointer', inside),
  dockSelectPlan: (id: PlanFilterId): Promise<void> => ipcRenderer.invoke('dock:select-plan', id),
  dockCreatePlan: (): Promise<void> => ipcRenderer.invoke('dock:create-plan'),
  dockShowMore: (): Promise<void> => ipcRenderer.invoke('dock:show-more'),
  dockContextMenu: (): Promise<void> => ipcRenderer.invoke('dock:context-menu'),
  onDockState: (cb: (state: DockViewState) => void) => {
    const handler = (_: Electron.IpcRendererEvent, state: DockViewState) => cb(state)
    ipcRenderer.on('dock:state', handler)
    return () => ipcRenderer.removeListener('dock:state', handler)
  },
  onShowCompleted: (cb: (enabled: boolean) => void) => {
    const handler = (_: Electron.IpcRendererEvent, enabled: boolean) => cb(enabled)
    ipcRenderer.on('ui:show-completed', handler)
    return () => ipcRenderer.removeListener('ui:show-completed', handler)
  },
  onFontSize: (cb: (size: FontSizeId) => void) => {
    const handler = (_: Electron.IpcRendererEvent, size: FontSizeId) => cb(size)
    ipcRenderer.on('ui:font-size', handler)
    return () => ipcRenderer.removeListener('ui:font-size', handler)
  },
  onFontFamily: (cb: (family: FontFamilyId) => void) => {
    const handler = (_: Electron.IpcRendererEvent, family: FontFamilyId) => cb(family)
    ipcRenderer.on('ui:font-family', handler)
    return () => ipcRenderer.removeListener('ui:font-family', handler)
  },
  onWindowState: (cb: (state: { maximized: boolean }) => void) => {
    const handler = (_: Electron.IpcRendererEvent, state: { maximized: boolean }) => cb(state)
    ipcRenderer.on('ui:window-state', handler)
    return () => ipcRenderer.removeListener('ui:window-state', handler)
  },
  onReminder: (cb: (payload: { taskId: string; title: string }) => void) => {
    const handler = (_: Electron.IpcRendererEvent, payload: { taskId: string; title: string }) =>
      cb(payload)
    ipcRenderer.on('ui:reminder', handler)
    return () => ipcRenderer.removeListener('ui:reminder', handler)
  },
}

contextBridge.exposeInMainWorld('todothings', api)

export type TodoThingsApi = typeof api
