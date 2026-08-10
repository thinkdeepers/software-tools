import {
  BrowserWindow,
  screen,
  type Display,
  type Rectangle,
} from 'electron'

export type DockEdge = 'left' | 'right' | 'top' | 'bottom'

/** 可见半透明细线厚度（经 setShape 裁剪，不受 Windows 最小窗口限制） */
const DOCK_LINE_PX = 2
/**
 * 实际窗口厚度：需不小于 Windows 最小窗口尺寸，避免被系统撑大后溢出到邻屏。
 * 窗口整体限制在当前屏 workArea 内，再用 setShape 只露出边缘细线。
 */
const STRIP_HIT_PX = 48
const HOVER_SLACK_PX = 6
const COLLAPSE_DELAY_MS = 900
const HOVER_POLL_MS = 80
const COLLAPSE_AFTER_MOVE_MS = 1200
const MIN_WIDTH = 280
const MIN_HEIGHT = 240
const STRIP_OPACITY = 0.45

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

/** 用窗口中心点定位显示器，避免贴边时误匹配到邻屏 */
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

  // 优先认作“已贴边”的方向，便于主动拖到底部/左侧停靠
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

/**
 * 感应条窗口矩形：完整落在当前屏 workArea 内（不会跨到邻屏）。
 * 可见细线再通过 setShape 裁到外边缘。
 */
function stripWindowBounds(
  edge: DockEdge,
  expanded: Rectangle,
  workArea: Rectangle,
): Rectangle {
  const hit = Math.min(STRIP_HIT_PX, workArea.width, workArea.height)
  const height = Math.min(Math.max(expanded.height, hit), workArea.height)
  const width = Math.min(Math.max(expanded.width, hit), workArea.width)
  let y = expanded.y
  let x = expanded.x
  if (y < workArea.y) y = workArea.y
  if (y + height > workArea.y + workArea.height) y = workArea.y + workArea.height - height
  if (x < workArea.x) x = workArea.x
  if (x + width > workArea.x + workArea.width) x = workArea.x + workArea.width - width

  switch (edge) {
    case 'left':
      return clampStripIntoWorkArea(
        { x: workArea.x, y, width: hit, height },
        workArea,
      )
    case 'right':
      return clampStripIntoWorkArea(
        { x: workArea.x + workArea.width - hit, y, width: hit, height },
        workArea,
      )
    case 'top':
      return clampStripIntoWorkArea(
        { x, y: workArea.y, width, height: hit },
        workArea,
      )
    case 'bottom':
      return clampStripIntoWorkArea(
        { x, y: workArea.y + workArea.height - hit, width, height: hit },
        workArea,
      )
  }
}

function stripShape(edge: DockEdge, bounds: Rectangle): Rectangle {
  const line = Math.min(DOCK_LINE_PX, bounds.width, bounds.height)
  switch (edge) {
    case 'left':
      return { x: 0, y: 0, width: line, height: bounds.height }
    case 'right':
      return { x: Math.max(0, bounds.width - line), y: 0, width: line, height: bounds.height }
    case 'top':
      return { x: 0, y: 0, width: bounds.width, height: line }
    case 'bottom':
      return { x: 0, y: Math.max(0, bounds.height - line), width: bounds.width, height: line }
  }
}

function snapExpandedToEdge(
  base: Rectangle,
  edge: DockEdge,
  workArea: Rectangle,
): Rectangle {
  let next = clampExpandedBounds(base, workArea)
  switch (edge) {
    case 'left':
      next = { ...next, x: workArea.x }
      break
    case 'right':
      next = { ...next, x: workArea.x + workArea.width - next.width }
      break
    case 'top':
      next = { ...next, y: workArea.y }
      break
    case 'bottom':
      next = { ...next, y: workArea.y + workArea.height - next.height }
      break
  }
  return next
}

export type EdgeDockChangePayload = {
  enabled: boolean
  collapsed: boolean
  edge: DockEdge | null
}

export class EdgeDockManager {
  private win: BrowserWindow | null = null
  private strip: BrowserWindow | null = null
  private enabled = false
  private collapsed = false
  private edge: DockEdge | null = null
  private savedBounds: Rectangle | null = null
  private displayId: number | null = null
  private collapseTimer: NodeJS.Timeout | null = null
  private pollTimer: NodeJS.Timeout | null = null
  private dragging = false
  private animating = false
  private readonly onChange: (state: EdgeDockChangePayload) => void

  constructor(onChange: (state: EdgeDockChangePayload) => void) {
    this.onChange = onChange
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
    this.stopPolling()
    this.destroyStrip()
    this.win = null
  }

  dispose() {
    if (this.enabled && this.collapsed) {
      this.expandImmediate()
    }
    this.detach()
    this.enabled = false
    this.collapsed = false
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
      this.stopPolling()
      if (this.collapsed) this.expandImmediate()
      this.destroyStrip()
      this.collapsed = false
      this.edge = null
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
      // 开启侧边停靠后不在任务栏显示应用按钮，仅保留托盘入口
      win.setSkipTaskbar(true)
      this.savedBounds = win.getBounds()
      this.displayId = displayForWindowBounds(this.savedBounds).id
    }
    this.startPolling()
    this.scheduleCollapse(200)
    this.emit()
  }

  /** Expand if collapsed (e.g. tray open / show window). */
  ensureExpanded() {
    if (!this.enabled) return
    this.clearCollapseTimer()
    if (this.collapsed) this.expandImmediate()
  }

  /**
   * Force collapse to an edge line.
   * Used when closing the window while edge-dock is enabled.
   */
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
    this.collapseImmediate()
  }

  private emit() {
    this.onChange(this.getState())
  }

  private clearCollapseTimer() {
    if (this.collapseTimer) {
      clearTimeout(this.collapseTimer)
      this.collapseTimer = null
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

  private ensureStrip(): BrowserWindow {
    if (this.strip && !this.strip.isDestroyed()) return this.strip

    this.strip = new BrowserWindow({
      width: STRIP_HIT_PX,
      height: STRIP_HIT_PX,
      minWidth: 1,
      minHeight: 1,
      frame: false,
      transparent: false,
      backgroundColor: '#ffffff',
      alwaysOnTop: true,
      skipTaskbar: true,
      resizable: false,
      movable: false,
      minimizable: false,
      maximizable: false,
      closable: false,
      focusable: false,
      hasShadow: false,
      thickFrame: false,
      show: false,
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
      },
    })

    this.strip.setMenu(null)
    this.strip.setMinimumSize(1, 1)
    this.strip.setAlwaysOnTop(true, 'screen-saver')
    this.strip.setOpacity(STRIP_OPACITY)
    void this.strip.loadURL(
      `data:text/html,${encodeURIComponent(
        '<!doctype html><html><head><meta charset="utf-8"></head>' +
          '<body style="margin:0;background:#ffffff;overflow:hidden"></body></html>',
      )}`,
    )

    this.strip.on('closed', () => {
      this.strip = null
    })

    return this.strip
  }

  private destroyStrip() {
    const strip = this.strip
    this.strip = null
    if (strip && !strip.isDestroyed()) {
      try {
        strip.setShape([])
      } catch {
        // ignore
      }
      strip.destroy()
    }
  }

  private hideStrip() {
    const strip = this.strip
    if (strip && !strip.isDestroyed()) {
      try {
        strip.setShape([])
      } catch {
        // ignore
      }
      if (strip.isVisible()) strip.hide()
    }
  }

  private placeStrip(edge: DockEdge, expanded: Rectangle, workArea: Rectangle) {
    // 横/竖切换时销毁重建，避免系统最小尺寸把窄条粘成宽条
    this.destroyStrip()
    const strip = this.ensureStrip()
    const desired = stripWindowBounds(edge, expanded, workArea)

    strip.setMinimumSize(1, 1)
    strip.setBounds(desired, false)
    strip.setSize(desired.width, desired.height)

    let actual = strip.getBounds()
    // 若系统仍放大了窗口，重新夹紧到当前屏内，避免右/底边溢到邻屏
    if (edge === 'right') {
      actual = {
        ...actual,
        x: workArea.x + workArea.width - actual.width,
        y: desired.y,
        height: desired.height,
      }
    } else if (edge === 'left') {
      actual = {
        ...actual,
        x: workArea.x,
        y: desired.y,
        width: Math.min(actual.width, STRIP_HIT_PX),
        height: desired.height,
      }
    } else if (edge === 'top') {
      actual = {
        ...actual,
        x: desired.x,
        y: workArea.y,
        width: desired.width,
        height: Math.min(actual.height, STRIP_HIT_PX),
      }
    } else {
      actual = {
        ...actual,
        x: desired.x,
        y: workArea.y + workArea.height - actual.height,
        width: desired.width,
        height: Math.min(actual.height, STRIP_HIT_PX),
      }
    }
    actual = clampStripIntoWorkArea(actual, workArea)
    // 再次贴边，保证整窗仍在本屏
    if (edge === 'right') actual.x = workArea.x + workArea.width - actual.width
    if (edge === 'left') actual.x = workArea.x
    if (edge === 'top') actual.y = workArea.y
    if (edge === 'bottom') actual.y = workArea.y + workArea.height - actual.height
    actual = clampStripIntoWorkArea(actual, workArea)

    strip.setBounds(actual, false)
    strip.setSize(actual.width, actual.height)

    const shape = stripShape(edge, actual)
    try {
      strip.setShape([shape])
    } catch {
      // setShape 不可用时至少保持半透明整块感应条
    }
    strip.setOpacity(STRIP_OPACITY)
    strip.setAlwaysOnTop(true, 'screen-saver')
    strip.showInactive()
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

    const bounds = win.getBounds()
    const cursor = screen.getCursorScreenPoint()
    if (pointInRect(cursor.x, cursor.y, bounds, HOVER_SLACK_PX)) return

    this.collapseImmediate()
  }

  private resolveWorkArea(bounds: Rectangle): { display: Display; workArea: Rectangle } {
    const display = displayById(this.displayId) ?? displayForWindowBounds(bounds)
    this.displayId = display.id
    return { display, workArea: display.workArea }
  }

  private collapseImmediate() {
    const win = this.win
    if (!win || win.isDestroyed() || this.collapsed) return
    if (win.isMaximized()) win.unmaximize()

    const bounds = this.savedBounds ?? win.getBounds()
    const { workArea } = this.resolveWorkArea(bounds)
    const edge = detectDockEdge(bounds, workArea)
    const expanded = clampExpandedBounds(bounds, workArea)

    this.savedBounds = expanded
    this.edge = edge
    this.animating = true

    this.placeStrip(edge, expanded, workArea)

    // Hide the full UI so the edge only shows the shaped line.
    if (win.isVisible()) win.hide()
    win.setSkipTaskbar(true)

    this.collapsed = true
    this.animating = false
    this.emit()
  }

  private expandImmediate() {
    const win = this.win
    if (!win || win.isDestroyed() || !this.collapsed) return

    const fallback = this.savedBounds ?? { x: 100, y: 100, width: 560, height: 520 }
    const { workArea } = this.resolveWorkArea(fallback)
    const edge = this.edge ?? detectDockEdge(fallback, workArea)
    const next = snapExpandedToEdge(fallback, edge, workArea)

    this.animating = true
    this.hideStrip()
    win.setMinimumSize(MIN_WIDTH, MIN_HEIGHT)
    win.setBounds(next, false)
    win.setAlwaysOnTop(true)
    win.setSkipTaskbar(true)
    if (!win.isVisible()) win.show()
    win.focus()
    this.savedBounds = next
    this.collapsed = false
    this.animating = false
    this.emit()
  }

  private pollCursor() {
    if (!this.enabled || this.dragging || this.animating) return
    const win = this.win
    if (!win || win.isDestroyed()) return

    const cursor = screen.getCursorScreenPoint()

    if (this.collapsed) {
      const strip = this.strip
      if (!strip || strip.isDestroyed()) return
      // 使用完整感应窗口矩形（含不可见 hit 区），便于移入触发
      if (pointInRect(cursor.x, cursor.y, strip.getBounds(), HOVER_SLACK_PX)) {
        this.expandImmediate()
      }
      return
    }

    if (win.isMaximized() || win.isMinimized() || !win.isVisible()) return

    const bounds = win.getBounds()
    if (pointInRect(cursor.x, cursor.y, bounds, HOVER_SLACK_PX)) {
      this.clearCollapseTimer()
      return
    }

    if (!this.collapseTimer) this.scheduleCollapse()
  }

  private readonly handleWillMove = () => {
    if (!this.enabled) return
    this.dragging = true
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
