import { screen, type BrowserWindow, type Rectangle } from 'electron'

export type DockEdge = 'left' | 'right' | 'top' | 'bottom'

const DOCK_VISIBLE_PX = 8
const COLLAPSE_DELAY_MS = 900
const HOVER_POLL_MS = 80
const HOVER_SLACK_PX = 4
const MIN_WIDTH = 280
const MIN_HEIGHT = 240

function pointInRect(x: number, y: number, rect: Rectangle, slack = 0): boolean {
  return (
    x >= rect.x - slack &&
    y >= rect.y - slack &&
    x <= rect.x + rect.width + slack &&
    y <= rect.y + rect.height + slack
  )
}

function detectDockEdge(bounds: Rectangle, workArea: Rectangle): DockEdge {
  const distLeft = Math.abs(bounds.x - workArea.x)
  const distRight = Math.abs(workArea.x + workArea.width - (bounds.x + bounds.width))
  const distTop = Math.abs(bounds.y - workArea.y)
  const distBottom = Math.abs(workArea.y + workArea.height - (bounds.y + bounds.height))
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

function collapsedBounds(edge: DockEdge, expanded: Rectangle, workArea: Rectangle): Rectangle {
  switch (edge) {
    case 'left':
      return {
        x: workArea.x,
        y: expanded.y,
        width: DOCK_VISIBLE_PX,
        height: expanded.height,
      }
    case 'right':
      return {
        x: workArea.x + workArea.width - DOCK_VISIBLE_PX,
        y: expanded.y,
        width: DOCK_VISIBLE_PX,
        height: expanded.height,
      }
    case 'top':
      return {
        x: expanded.x,
        y: workArea.y,
        width: expanded.width,
        height: DOCK_VISIBLE_PX,
      }
    case 'bottom':
      return {
        x: expanded.x,
        y: workArea.y + workArea.height - DOCK_VISIBLE_PX,
        width: expanded.width,
        height: DOCK_VISIBLE_PX,
      }
  }
}

export type EdgeDockChangePayload = {
  enabled: boolean
  collapsed: boolean
  edge: DockEdge | null
}

export class EdgeDockManager {
  private win: BrowserWindow | null = null
  private enabled = false
  private collapsed = false
  private edge: DockEdge | null = null
  private savedBounds: Rectangle | null = null
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
      this.collapsed = false
      this.edge = null
      this.savedBounds = null
      this.emit()
      return
    }

    const win = this.win
    if (win && !win.isDestroyed()) {
      if (win.isMaximized()) win.unmaximize()
      if (win.isMinimized()) win.restore()
      if (!win.isVisible()) win.show()
      win.setAlwaysOnTop(true)
      this.savedBounds = win.getBounds()
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

  private collapseImmediate() {
    const win = this.win
    if (!win || win.isDestroyed() || this.collapsed) return
    if (win.isMaximized()) win.unmaximize()

    const bounds = win.getBounds()
    const display = screen.getDisplayMatching(bounds)
    const workArea = display.workArea
    const edge = detectDockEdge(bounds, workArea)
    const expanded = clampExpandedBounds(bounds, workArea)

    this.savedBounds = expanded
    this.edge = edge
    this.animating = true
    win.setMinimumSize(DOCK_VISIBLE_PX, DOCK_VISIBLE_PX)
    win.setBounds(collapsedBounds(edge, expanded, workArea), false)
    this.collapsed = true
    this.animating = false
    this.emit()
  }

  private expandImmediate() {
    const win = this.win
    if (!win || win.isDestroyed() || !this.collapsed) return

    const fallback = win.getBounds()
    const display = screen.getDisplayMatching(this.savedBounds ?? fallback)
    const workArea = display.workArea
    const edge = this.edge ?? detectDockEdge(this.savedBounds ?? fallback, workArea)
    const base = this.savedBounds ?? {
      x: fallback.x,
      y: fallback.y,
      width: Math.max(fallback.width, MIN_WIDTH),
      height: Math.max(fallback.height, MIN_HEIGHT),
    }

    // Keep docked flush to the edge when expanding.
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

    this.animating = true
    win.setMinimumSize(MIN_WIDTH, MIN_HEIGHT)
    win.setBounds(next, false)
    if (!win.isVisible()) win.show()
    this.savedBounds = next
    this.collapsed = false
    this.animating = false
    this.emit()
  }

  private pollCursor() {
    if (!this.enabled || this.dragging || this.animating) return
    const win = this.win
    if (!win || win.isDestroyed()) return
    if (win.isMaximized() || win.isMinimized() || !win.isVisible()) return

    const cursor = screen.getCursorScreenPoint()
    const bounds = win.getBounds()

    if (this.collapsed) {
      if (pointInRect(cursor.x, cursor.y, bounds, HOVER_SLACK_PX)) {
        this.expandImmediate()
      }
      return
    }

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
    }
    this.scheduleCollapse(COLLAPSE_AFTER_MOVE_MS)
  }

  private readonly handleBlur = () => {
    if (!this.enabled) return
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

const COLLAPSE_AFTER_MOVE_MS = 1200
