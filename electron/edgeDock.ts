import {
  BrowserWindow,
  Menu,
  screen,
  type Display,
  type Rectangle,
} from 'electron'
import type { DockEdge, DockPlan, DockViewState, PlanFilterId } from './types'

export type { DockEdge }

/** Hold My Notes: 12 pt pill, one coloured dash per plan */
const PILL_THICK_PX = 16
const PILL_PAD_PX = 5
const DASH_MAIN_PX = 10
const DASH_GAP_PX = 3
const FAN_TAB_ALONG_PX = 36
const FAN_TAB_ACROSS_PX = 156
const FAN_OVERLAP_PX = 8
const FAN_PAD_PX = 8
const MAX_VISIBLE_TABS = 8
const HOVER_SLACK_PX = 8
const COLLAPSE_DELAY_MS = 900
const FAN_COLLAPSE_MS = 700
const HOVER_POLL_MS = 80
const COLLAPSE_AFTER_MOVE_MS = 1200
const MIN_WIDTH = 280
const MIN_HEIGHT = 240
const SLIDE_MS = 180

export type EdgeDockChangePayload = {
  enabled: boolean
  collapsed: boolean
  edge: DockEdge | null
}

export type EdgeDockHooks = {
  preloadPath: string
  loadDock: (win: BrowserWindow) => void
  onChange: (state: EdgeDockChangePayload) => void
  onSelectPlan: (id: PlanFilterId) => void
  onCreatePlan: () => void
  onDisable: () => void
}

function pointInRect(x: number, y: number, rect: Rectangle, slack = 0): boolean {
  return (
    x >= rect.x - slack &&
    y >= rect.y - slack &&
    x <= rect.x + rect.width + slack &&
    y <= rect.y + rect.height + slack
  )
}

function displayById(id: number | null): Display | null {
  if (id == null) return null
  return screen.getAllDisplays().find((d) => d.id === id) ?? null
}

function displayForWindowBounds(bounds: Rectangle): Display {
  const cx = bounds.x + Math.floor(bounds.width / 2)
  const cy = bounds.y + Math.floor(bounds.height / 2)
  return screen.getDisplayNearestPoint({ x: cx, y: cy })
}

function detectDockEdge(bounds: Rectangle, workArea: Rectangle): DockEdge {
  const distLeft = Math.abs(bounds.x - workArea.x)
  const distRight = Math.abs(workArea.x + workArea.width - (bounds.x + bounds.width))
  const distTop = Math.abs(bounds.y - workArea.y)
  const distBottom = Math.abs(workArea.y + workArea.height - (bounds.y + bounds.height))

  const SNAP = 96
  const touching: { edge: DockEdge; dist: number }[] = []
  if (distLeft <= SNAP) touching.push({ edge: 'left', dist: distLeft })
  if (distRight <= SNAP) touching.push({ edge: 'right', dist: distRight })
  if (distTop <= SNAP) touching.push({ edge: 'top', dist: distTop })
  if (distBottom <= SNAP) touching.push({ edge: 'bottom', dist: distBottom })
  if (touching.length > 0) {
    touching.sort((a, b) => a.dist - b.dist)
    return touching[0]!.edge
  }

  const min = Math.min(distLeft, distRight, distTop, distBottom)
  if (min === distLeft) return 'left'
  if (min === distRight) return 'right'
  if (min === distTop) return 'top'
  return 'bottom'
}

function clampExpandedBounds(bounds: Rectangle, workArea: Rectangle): Rectangle {
  const width = Math.min(Math.max(bounds.width, MIN_WIDTH), workArea.width)
  const height = Math.min(Math.max(bounds.height, MIN_HEIGHT), workArea.height)
  let x = bounds.x
  let y = bounds.y
  if (x < workArea.x) x = workArea.x
  if (y < workArea.y) y = workArea.y
  if (x + width > workArea.x + workArea.width) x = workArea.x + workArea.width - width
  if (y + height > workArea.y + workArea.height) y = workArea.y + workArea.height - height
  return { x, y, width, height }
}

function clampStripIntoWorkArea(bounds: Rectangle, workArea: Rectangle): Rectangle {
  const width = Math.min(bounds.width, workArea.width)
  const height = Math.min(bounds.height, workArea.height)
  let x = bounds.x
  let y = bounds.y
  if (x < workArea.x) x = workArea.x
  if (y < workArea.y) y = workArea.y
  if (x + width > workArea.x + workArea.width) x = workArea.x + workArea.width - width
  if (y + height > workArea.y + workArea.height) y = workArea.y + workArea.height - height
  return { x, y, width, height }
}

function visibleTabCount(planCount: number, fanned: boolean): number {
  if (planCount <= 0) return 1
  const shown = Math.min(planCount, MAX_VISIBLE_TABS)
  const extra = planCount > MAX_VISIBLE_TABS ? 1 : 0
  const plus = fanned ? 1 : 0
  return shown + extra + plus
}

function sleepDashCount(planCount: number): number {
  if (planCount <= 0) return 1
  return Math.min(planCount, MAX_VISIBLE_TABS) + (planCount > MAX_VISIBLE_TABS ? 1 : 0)
}

function sleepVisualSize(planCount: number): { across: number; along: number } {
  const dashes = sleepDashCount(planCount)
  return {
    across: PILL_THICK_PX,
    along: PILL_PAD_PX * 2 + dashes * DASH_MAIN_PX + Math.max(0, dashes - 1) * DASH_GAP_PX,
  }
}

function fanVisualSize(planCount: number): { across: number; along: number } {
  const tabs = visibleTabCount(planCount, true)
  return {
    across: FAN_TAB_ACROSS_PX + FAN_PAD_PX * 2,
    along:
      FAN_PAD_PX * 2 +
      tabs * (FAN_TAB_ALONG_PX - FAN_OVERLAP_PX) +
      FAN_OVERLAP_PX,
  }
}

const EDGES: DockEdge[] = ['right', 'bottom', 'left', 'top']

export class EdgeDockManager {
  private win: BrowserWindow | null = null
  private dock: BrowserWindow | null = null
  private dockAxis: 'h' | 'v' | null = null
  private dockReady = false
  private enabled = false
  private collapsed = false
  private fanned = false
  private edge: DockEdge | null = null
  private edgePinned = false
  private savedBounds: Rectangle | null = null
  private displayId: number | null = null
  private collapseTimer: NodeJS.Timeout | null = null
  private fanTimer: NodeJS.Timeout | null = null
  private pollTimer: NodeJS.Timeout | null = null
  private dragging = false
  private animating = false
  private motionGen = 0
  private plans: DockPlan[] = []
  private selectedId: string | null = null
  private overflowFrom = 0
  private lastLayout = {
    windowSize: { width: PILL_THICK_PX, height: 48 },
    visual: { x: 0, y: 0, width: PILL_THICK_PX, height: 48 },
  }
  private readonly hooks: EdgeDockHooks

  constructor(hooks: EdgeDockHooks) {
    this.hooks = hooks
  }

  isEnabled() {
    return this.enabled
  }

  isCollapsed() {
    return this.collapsed
  }

  getState(): EdgeDockChangePayload {
    return {
      enabled: this.enabled,
      collapsed: this.collapsed,
      edge: this.edge,
    }
  }

  attach(win: BrowserWindow) {
    this.detach()
    this.win = win

    win.on('will-move', this.handleWillMove)
    win.on('moved', this.handleMoved)
    win.on('will-resize', this.handleWillResize)
    win.on('resized', this.handleResized)
    win.on('blur', this.handleBlur)
    win.on('focus', this.handleFocus)
    win.on('minimize', this.handleMinimize)
    win.on('restore', this.handleRestore)
    win.on('leave-full-screen', this.handleRestore)
  }

  detach() {
    const win = this.win
    if (win && !win.isDestroyed()) {
      win.removeListener('will-move', this.handleWillMove)
      win.removeListener('moved', this.handleMoved)
      win.removeListener('will-resize', this.handleWillResize)
      win.removeListener('resized', this.handleResized)
      win.removeListener('blur', this.handleBlur)
      win.removeListener('focus', this.handleFocus)
      win.removeListener('minimize', this.handleMinimize)
      win.removeListener('restore', this.handleRestore)
      win.removeListener('leave-full-screen', this.handleRestore)
    }
    this.clearCollapseTimer()
    this.clearFanTimer()
    this.stopPolling()
    this.destroyDock()
    this.win = null
  }

  dispose() {
    if (this.enabled && this.collapsed) {
      this.expandImmediate()
    }
    this.detach()
    this.enabled = false
    this.collapsed = false
    this.fanned = false
    this.edge = null
    this.savedBounds = null
    this.displayId = null
  }

  setEnabled(enabled: boolean) {
    if (this.enabled === enabled) {
      this.emit()
      return
    }
    this.enabled = enabled
    if (!enabled) {
      this.clearCollapseTimer()
      this.clearFanTimer()
      this.stopPolling()
      this.fanned = false
      if (this.collapsed) this.expandImmediate()
      this.destroyDock()
      this.collapsed = false
      this.edge = null
      this.edgePinned = false
      this.savedBounds = null
      this.displayId = null
      const win = this.win
      if (win && !win.isDestroyed()) {
        win.setSkipTaskbar(false)
      }
      this.emit()
      return
    }

    const win = this.win
    if (win && !win.isDestroyed()) {
      if (win.isMaximized()) win.unmaximize()
      if (win.isMinimized()) win.restore()
      if (!win.isVisible()) win.show()
      win.setAlwaysOnTop(true)
      win.setSkipTaskbar(true)
      this.savedBounds = win.getBounds()
      this.displayId = displayForWindowBounds(this.savedBounds).id
      const { workArea } = this.resolveWorkArea(this.savedBounds)
      this.edge = detectDockEdge(this.savedBounds, workArea)
    }
    this.startPolling()
    this.placeDock()
    this.scheduleCollapse(200)
    this.emit()
  }

  setPlans(plans: DockPlan[], selectedId: string | null) {
    this.plans = plans
    this.selectedId = selectedId
    if (this.overflowFrom >= Math.max(plans.length, 1)) this.overflowFrom = 0
    if (this.enabled) this.placeDock()
    else this.sendDockState()
  }

  markDockReady() {
    this.dockReady = true
    this.sendDockState()
  }

  setPointerInside(inside: boolean) {
    if (!this.enabled) return
    if (inside) {
      this.clearFanTimer()
      this.clearCollapseTimer()
      this.setFanned(true)
      return
    }
    this.scheduleFanClose()
    if (!this.collapsed) this.scheduleCollapse()
  }

  selectFromDock(id: PlanFilterId) {
    if (!this.enabled) return
    this.selectedId = id === 'all' ? null : id
    this.setFanned(false)
    this.hooks.onSelectPlan(id)
    this.expandImmediate()
  }

  createFromDock() {
    if (!this.enabled) return
    this.setFanned(false)
    this.expandImmediate()
    this.hooks.onCreatePlan()
  }

  showMore() {
    if (this.plans.length <= MAX_VISIBLE_TABS) return
    this.overflowFrom = (this.overflowFrom + MAX_VISIBLE_TABS) % this.plans.length
    this.placeDock()
  }

  cycleEdge() {
    if (!this.enabled) return
    const current = this.edge ?? 'right'
    const next = EDGES[(EDGES.indexOf(current) + 1) % EDGES.length]!
    this.edge = next
    this.edgePinned = true
    if (!this.collapsed) this.repositionExpanded()
    this.placeDock()
    this.emit()
  }

  showDockMenu() {
    const menu = Menu.buildFromTemplate([
      {
        label: '打开所有计划',
        click: () => this.selectFromDock('all'),
      },
      {
        label: '新建计划',
        click: () => this.createFromDock(),
      },
      { type: 'separator' },
      {
        label: '换边停靠',
        click: () => this.cycleEdge(),
      },
      { type: 'separator' },
      {
        label: '退出侧边停靠',
        click: () => this.hooks.onDisable(),
      },
    ])
    menu.popup({ window: this.dock ?? this.win ?? undefined })
  }

  ensureExpanded() {
    if (!this.enabled) return
    this.clearCollapseTimer()
    this.setFanned(false)
    if (this.collapsed) this.expandImmediate()
  }

  collapseNow() {
    if (!this.enabled) return
    this.clearCollapseTimer()
    const win = this.win
    if (!win || win.isDestroyed()) return
    if (win.isMaximized()) win.unmaximize()
    if (win.isMinimized()) win.restore()
    if (!this.collapsed) {
      this.savedBounds = win.getBounds()
      this.displayId = displayForWindowBounds(this.savedBounds).id
    }
    this.setFanned(false)
    this.collapseImmediate()
  }

  private emit() {
    this.hooks.onChange(this.getState())
  }

  private clearCollapseTimer() {
    if (this.collapseTimer) {
      clearTimeout(this.collapseTimer)
      this.collapseTimer = null
    }
  }

  private clearFanTimer() {
    if (this.fanTimer) {
      clearTimeout(this.fanTimer)
      this.fanTimer = null
    }
  }

  private startPolling() {
    if (this.pollTimer) return
    this.pollTimer = setInterval(() => this.pollCursor(), HOVER_POLL_MS)
  }

  private stopPolling() {
    if (this.pollTimer) {
      clearInterval(this.pollTimer)
      this.pollTimer = null
    }
  }

  private setFanned(fanned: boolean) {
    if (this.fanned === fanned) return
    this.fanned = fanned
    if (this.enabled) this.placeDock()
  }

  private scheduleFanClose() {
    this.clearFanTimer()
    this.fanTimer = setTimeout(() => {
      this.fanTimer = null
      if (this.pointerOverDock()) return
      this.setFanned(false)
    }, FAN_COLLAPSE_MS)
  }

  private ensureDock(bounds: Rectangle): BrowserWindow {
    const axis = this.edge === 'top' || this.edge === 'bottom' ? 'h' : 'v'
    if (this.dock && !this.dock.isDestroyed() && this.dockAxis === axis) {
      return this.dock
    }
    this.destroyDock()
    this.dockAxis = axis
    this.dockReady = false

    this.dock = new BrowserWindow({
      x: bounds.x,
      y: bounds.y,
      width: bounds.width,
      height: bounds.height,
      minWidth: 1,
      minHeight: 1,
      frame: false,
      transparent: true,
      backgroundColor: '#00000000',
      alwaysOnTop: true,
      skipTaskbar: true,
      resizable: false,
      movable: false,
      minimizable: false,
      maximizable: false,
      closable: false,
      focusable: true,
      hasShadow: false,
      thickFrame: false,
      fullscreenable: false,
      show: false,
      type: process.platform === 'linux' ? 'notification' : undefined,
      webPreferences: {
        preload: this.hooks.preloadPath,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: false,
        backgroundThrottling: false,
      },
    })

    this.dock.setMenu(null)
    this.dock.setMinimumSize(1, 1)
    this.dock.setAlwaysOnTop(true, 'screen-saver')
    this.dock.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
    this.hooks.loadDock(this.dock)

    this.dock.on('closed', () => {
      this.dock = null
      this.dockReady = false
      this.dockAxis = null
    })

    return this.dock
  }

  private destroyDock() {
    const dock = this.dock
    this.dock = null
    this.dockReady = false
    this.dockAxis = null
    if (dock && !dock.isDestroyed()) {
      try {
        dock.setShape([])
      } catch {
        // ignore
      }
      dock.destroy()
    }
  }

  private hideDock() {
    const dock = this.dock
    if (dock && !dock.isDestroyed() && dock.isVisible()) dock.hide()
  }

  private dockVisualFor(fanned: boolean): { across: number; along: number } {
    return fanned ? fanVisualSize(this.plans.length) : sleepVisualSize(this.plans.length)
  }

  /** Window stays fan-sized so the WM does not recenter a shrinking 16px strip. */
  private dockWindowBounds(edge: DockEdge, workArea: Rectangle, expanded: Rectangle): Rectangle {
    const { across, along } = fanVisualSize(this.plans.length)
    const vertical = edge === 'left' || edge === 'right'
    const width = vertical ? across : along
    const height = vertical ? along : across
    let x = expanded.x
    let y = expanded.y
    if (vertical) {
      y = Math.min(Math.max(expanded.y, workArea.y), workArea.y + workArea.height - height)
      x = edge === 'left' ? workArea.x : workArea.x + workArea.width - width
    } else {
      x = Math.min(Math.max(expanded.x, workArea.x), workArea.x + workArea.width - width)
      y = edge === 'top' ? workArea.y : workArea.y + workArea.height - height
    }
    return clampStripIntoWorkArea({ x, y, width, height }, workArea)
  }

  private pinToEdge(bounds: Rectangle, edge: DockEdge, workArea: Rectangle): Rectangle {
    const next = { ...bounds }
    if (edge === 'right') next.x = workArea.x + workArea.width - next.width
    if (edge === 'left') next.x = workArea.x
    if (edge === 'top') next.y = workArea.y
    if (edge === 'bottom') next.y = workArea.y + workArea.height - next.height
    return clampStripIntoWorkArea(next, workArea)
  }

  private visualInWindow(edge: DockEdge, actual: Rectangle): Rectangle {
    const { across, along } = this.dockVisualFor(this.fanned)
    const vertical = edge === 'left' || edge === 'right'
    const width = Math.min(vertical ? across : along, actual.width)
    const height = Math.min(vertical ? along : across, actual.height)
    let x = 0
    let y = 0
    if (edge === 'right') x = Math.max(0, actual.width - width)
    if (edge === 'bottom') y = Math.max(0, actual.height - height)
    return { x, y, width, height }
  }

  private applyDockPlacement(dock: BrowserWindow, pinned: Rectangle, edge: DockEdge) {
    dock.setMinimumSize(1, 1)
    dock.setBounds(pinned, false)
    dock.setPosition(pinned.x, pinned.y, false)
    const { workArea } = this.resolveWorkArea(pinned)
    let actual = this.pinToEdge(dock.getBounds(), edge, workArea)
    const now = dock.getBounds()
    if (actual.x !== now.x || actual.y !== now.y) {
      dock.setBounds(actual, false)
    }
    actual = dock.getBounds()
    const visual = this.visualInWindow(edge, actual)
    this.lastLayout = {
      windowSize: { width: actual.width, height: actual.height },
      visual,
    }
    try {
      dock.setShape([visual])
    } catch {
      // setShape 不可用时仍靠透明窗口露出胶囊
    }
    dock.setAlwaysOnTop(true, 'screen-saver')
  }

  private placeDock() {
    if (!this.enabled) {
      this.hideDock()
      return
    }
    const fallback = this.savedBounds ?? this.win?.getBounds() ?? {
      x: 100,
      y: 160,
      width: 560,
      height: 520,
    }
    const { workArea } = this.resolveWorkArea(fallback)
    const edge =
      this.edgePinned && this.edge
        ? this.edge
        : detectDockEdge(this.savedBounds ?? fallback, workArea)
    this.edge = edge

    const desired = this.dockWindowBounds(edge, workArea, this.savedBounds ?? fallback)
    const dock = this.ensureDock(desired)
    const pinned = this.pinToEdge(desired, edge, workArea)
    this.applyDockPlacement(dock, pinned, edge)
    if (!dock.isVisible()) dock.showInactive()
    this.applyDockPlacement(dock, this.pinToEdge(dock.getBounds(), edge, workArea), edge)
    this.sendDockState()
  }

  private snapDockIfDrifted() {
    if (!this.enabled || !this.edge) return
    const dock = this.dock
    if (!dock || dock.isDestroyed() || !dock.isVisible()) return
    const fallback = this.savedBounds ?? this.win?.getBounds() ?? dock.getBounds()
    const { workArea } = this.resolveWorkArea(fallback)
    const desired = this.dockWindowBounds(this.edge, workArea, fallback)
    const pinned = this.pinToEdge(desired, this.edge, workArea)
    const actual = dock.getBounds()
    const drifted =
      Math.abs(actual.x - pinned.x) > 4 ||
      Math.abs(actual.y - pinned.y) > 4 ||
      Math.abs(actual.width - pinned.width) > 12 ||
      Math.abs(actual.height - pinned.height) > 12
    if (drifted) this.applyDockPlacement(dock, pinned, this.edge)
  }

  private sendDockState() {
    const dock = this.dock
    if (!dock || dock.isDestroyed() || !this.dockReady) return
    const payload: DockViewState = {
      edge: this.edge ?? 'right',
      fanned: this.fanned,
      plans: this.plans,
      selectedId: this.selectedId,
      overflowFrom: this.overflowFrom,
      windowSize: this.lastLayout.windowSize,
      visual: this.lastLayout.visual,
    }
    dock.webContents.send('dock:state', payload)
  }

  private scheduleCollapse(delay = COLLAPSE_DELAY_MS) {
    if (!this.enabled || this.collapsed || this.dragging || this.animating) return
    const win = this.win
    if (!win || win.isDestroyed()) return
    if (win.isMaximized() || win.isMinimized() || !win.isVisible()) return

    this.clearCollapseTimer()
    this.collapseTimer = setTimeout(() => {
      this.collapseTimer = null
      this.collapseIfIdle()
    }, delay)
  }

  private collapseIfIdle() {
    if (!this.enabled || this.collapsed || this.dragging || this.animating) return
    const win = this.win
    if (!win || win.isDestroyed()) return
    if (win.isMaximized() || win.isMinimized() || !win.isVisible()) return
    if (this.pointerOverMain() || this.pointerOverDock()) return
    this.setFanned(false)
    this.collapseImmediate()
  }

  private resolveWorkArea(bounds: Rectangle): { display: Display; workArea: Rectangle } {
    const display = displayById(this.displayId) ?? displayForWindowBounds(bounds)
    this.displayId = display.id
    return { display, workArea: display.workArea }
  }

  private dockReserve(edge: DockEdge): number {
    return this.dockVisualFor(false).across + 4
  }

  private snapExpandedToEdge(base: Rectangle, edge: DockEdge, workArea: Rectangle): Rectangle {
    let next = clampExpandedBounds(base, workArea)
    const inset = this.dockReserve(edge)
    switch (edge) {
      case 'left':
        next = { ...next, x: workArea.x + inset }
        break
      case 'right':
        next = { ...next, x: workArea.x + workArea.width - next.width - inset }
        break
      case 'top':
        next = { ...next, y: workArea.y + inset }
        break
      case 'bottom':
        next = { ...next, y: workArea.y + workArea.height - next.height - inset }
        break
    }
    return clampExpandedBounds(next, workArea)
  }

  private slideOrigin(to: Rectangle, edge: DockEdge): Rectangle {
    switch (edge) {
      case 'right':
        return { ...to, x: to.x + Math.min(72, to.width) }
      case 'left':
        return { ...to, x: to.x - Math.min(72, to.width) }
      case 'top':
        return { ...to, y: to.y - Math.min(56, to.height) }
      case 'bottom':
        return { ...to, y: to.y + Math.min(56, to.height) }
    }
  }

  private animateBounds(
    win: BrowserWindow,
    from: Rectangle,
    to: Rectangle,
    ms: number,
    done: () => void,
  ) {
    const steps = Math.max(6, Math.round(ms / 20))
    let i = 0
    const tick = () => {
      if (win.isDestroyed()) {
        done()
        return
      }
      i += 1
      const t = i / steps
      const e = 1 - (1 - t) * (1 - t)
      win.setBounds(
        {
          x: Math.round(from.x + (to.x - from.x) * e),
          y: Math.round(from.y + (to.y - from.y) * e),
          width: Math.round(from.width + (to.width - from.width) * e),
          height: Math.round(from.height + (to.height - from.height) * e),
        },
        false,
      )
      if (i >= steps) done()
      else setTimeout(tick, ms / steps)
    }
    win.setBounds(from, false)
    tick()
  }

  private collapseImmediate() {
    const win = this.win
    if (!win || win.isDestroyed() || this.collapsed) return
    if (win.isMaximized()) win.unmaximize()

    const bounds = this.savedBounds ?? win.getBounds()
    const { workArea } = this.resolveWorkArea(bounds)
    const edge =
      this.edgePinned && this.edge ? this.edge : detectDockEdge(bounds, workArea)
    const expanded = clampExpandedBounds(bounds, workArea)

    this.savedBounds = expanded
    this.edge = edge
    const gen = ++this.motionGen
    this.animating = true
    this.placeDock()

    const hide = () => {
      if (gen !== this.motionGen) return
      if (!win.isDestroyed() && win.isVisible()) win.hide()
      win.setSkipTaskbar(true)
      this.collapsed = true
      this.animating = false
      this.placeDock()
      this.emit()
    }

    if (win.isVisible()) {
      const from = win.getBounds()
      const to = this.slideOrigin(from, edge)
      this.animateBounds(win, from, to, SLIDE_MS, hide)
    } else {
      hide()
    }
  }

  private expandImmediate() {
    const win = this.win
    if (!win || win.isDestroyed()) return

    const fallback = this.savedBounds ?? { x: 100, y: 100, width: 560, height: 520 }
    const { workArea } = this.resolveWorkArea(fallback)
    const edge = this.edge ?? detectDockEdge(fallback, workArea)
    const next = this.snapExpandedToEdge(fallback, edge, workArea)

    const gen = ++this.motionGen
    this.animating = true
    this.placeDock()
    win.setMinimumSize(MIN_WIDTH, MIN_HEIGHT)
    win.setAlwaysOnTop(true)
    win.setSkipTaskbar(true)

    const finish = () => {
      if (gen !== this.motionGen) return
      if (!win.isDestroyed()) {
        win.setBounds(next, false)
        if (!win.isVisible()) win.show()
        win.focus()
      }
      this.savedBounds = next
      this.collapsed = false
      this.animating = false
      this.placeDock()
      this.emit()
    }

    if (this.collapsed || !win.isVisible()) {
      const from = this.slideOrigin(next, edge)
      win.setBounds(from, false)
      if (!win.isVisible()) win.show()
      this.animateBounds(win, from, next, SLIDE_MS, finish)
      return
    }

    finish()
  }

  private repositionExpanded() {
    const win = this.win
    if (!win || win.isDestroyed() || this.collapsed) return
    const fallback = this.savedBounds ?? win.getBounds()
    const { workArea } = this.resolveWorkArea(fallback)
    const edge = this.edge ?? detectDockEdge(fallback, workArea)
    const next = this.snapExpandedToEdge(fallback, edge, workArea)
    win.setBounds(next, false)
    this.savedBounds = next
  }

  private pointerOverDock(): boolean {
    const dock = this.dock
    if (!dock || dock.isDestroyed() || !dock.isVisible()) return false
    const cursor = screen.getCursorScreenPoint()
    return pointInRect(cursor.x, cursor.y, dock.getBounds(), HOVER_SLACK_PX)
  }

  private pointerOverMain(): boolean {
    const win = this.win
    if (!win || win.isDestroyed() || !win.isVisible()) return false
    const cursor = screen.getCursorScreenPoint()
    return pointInRect(cursor.x, cursor.y, win.getBounds(), HOVER_SLACK_PX)
  }

  private pollCursor() {
    if (!this.enabled || this.dragging || this.animating) return
    this.snapDockIfDrifted()
    const win = this.win
    if (!win || win.isDestroyed()) return

    const overDock = this.pointerOverDock()
    const overMain = this.pointerOverMain()

    if (overDock) {
      this.clearCollapseTimer()
      this.clearFanTimer()
      this.setFanned(true)
      return
    }

    if (this.fanned && !overDock) {
      if (!this.fanTimer) this.scheduleFanClose()
    }

    if (this.collapsed) return

    if (win.isMaximized() || win.isMinimized() || !win.isVisible()) return

    if (overMain) {
      this.clearCollapseTimer()
      return
    }

    if (!this.collapseTimer) this.scheduleCollapse()
  }

  private readonly handleWillMove = () => {
    if (!this.enabled) return
    this.dragging = true
    this.edgePinned = false
    this.clearCollapseTimer()
    if (this.collapsed) this.expandImmediate()
  }

  private readonly handleMoved = () => {
    if (!this.enabled) return
    this.dragging = false
    const win = this.win
    if (win && !win.isDestroyed() && !this.collapsed) {
      this.savedBounds = win.getBounds()
      this.displayId = displayForWindowBounds(this.savedBounds).id
      const { workArea } = this.resolveWorkArea(this.savedBounds)
      this.edge = detectDockEdge(this.savedBounds, workArea)
      this.placeDock()
    }
    this.scheduleCollapse(COLLAPSE_AFTER_MOVE_MS)
  }

  private readonly handleWillResize = () => {
    if (!this.enabled) return
    this.dragging = true
    this.clearCollapseTimer()
    if (this.collapsed) this.expandImmediate()
  }

  private readonly handleResized = () => {
    if (!this.enabled) return
    this.dragging = false
    const win = this.win
    if (win && !win.isDestroyed() && !this.collapsed) {
      this.savedBounds = win.getBounds()
      this.displayId = displayForWindowBounds(this.savedBounds).id
      this.placeDock()
    }
    this.scheduleCollapse(COLLAPSE_AFTER_MOVE_MS)
  }

  private readonly handleBlur = () => {
    if (!this.enabled || this.collapsed) return
    this.scheduleCollapse(400)
  }

  private readonly handleFocus = () => {
    if (!this.enabled) return
    this.clearCollapseTimer()
    if (this.collapsed) this.expandImmediate()
  }

  private readonly handleMinimize = () => {
    this.clearCollapseTimer()
  }

  private readonly handleRestore = () => {
    if (!this.enabled) return
    this.scheduleCollapse()
  }
}
