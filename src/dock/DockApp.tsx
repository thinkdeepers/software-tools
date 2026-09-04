import { useEffect, useState } from 'react'
import { normalizePlanColor } from '../planColors'
import type { DockEdge } from '../types'

type DockTask = { id: string; title: string; completed: boolean; parentId: string | null }
type DockPlan = { id: string; title: string; color: string; tasks: DockTask[] }

type DockViewState = {
  edge: DockEdge
  fanned: boolean
  previewId: string | null
  plans: DockPlan[]
  selectedId: string | null
  windowSize: { width: number; height: number }
  visual: { x: number; y: number; width: number; height: number }
}

export function DockApp() {
  const [state, setState] = useState<DockViewState | null>(null)
  const [hoverId, setHoverId] = useState<string | null>(null)

  useEffect(() => {
    document.documentElement.classList.add('dock-root')
    const off = window.todothings.onDockState((next) => {
      setState(next)
      if (!next.fanned) setHoverId(null)
    })
    void window.todothings.dockReady()
    return () => {
      off()
      document.documentElement.classList.remove('dock-root')
    }
  }, [])

  if (!state) return null

  const plans = state.plans
  const openId = state.fanned ? hoverId : null
  const box = state.fanned
    ? { left: 0, top: 0, width: state.windowSize.width, height: state.windowSize.height }
    : {
        left: state.visual.x,
        top: state.visual.y,
        width: state.visual.width,
        height: state.visual.height,
      }

  function hoverPlan(id: string | null) {
    setHoverId(id)
    void window.todothings.dockHoverPlan(id)
  }

  return (
    <div className="deck-stage">
      <div
        className={`deck edge-${state.edge} ${state.fanned ? 'fanned' : 'sleeping'}${openId ? ' previewing' : ''}`}
        style={box}
        onMouseEnter={() => window.todothings.dockPointer(true)}
        onMouseLeave={() => {
          hoverPlan(null)
          window.todothings.dockPointer(false)
        }}
        onContextMenu={(event) => {
          event.preventDefault()
          window.todothings.dockContextMenu()
        }}
      >
        <div className="tab-stack">
          {plans.length === 0 && (
            <div className="tab-wrap color-paper">
              <button
                type="button"
                className="tab color-paper"
                title="打开 TodoThings"
                onClick={() => window.todothings.dockSelectPlan('all')}
              >
                <span className="spine" />
                <span className="label">计划</span>
              </button>
            </div>
          )}
          {plans.map((plan, index) => {
            const color = normalizePlanColor(plan.color)
            const open = openId === plan.id
            return (
              <div
                key={plan.id}
                className={`tab-wrap color-${color}${open ? ' open' : ''}${state.selectedId === plan.id ? ' selected' : ''}`}
                style={{ animationDelay: state.fanned ? `${index * 40}ms` : '0ms' }}
                onMouseEnter={() => {
                  if (state.fanned) hoverPlan(plan.id)
                }}
              >
                <button
                  type="button"
                  className={`tab color-${color}`}
                  title={plan.title}
                  onClick={() => window.todothings.dockSelectPlan(plan.id)}
                >
                  <span className="spine" />
                  <span className="label">{plan.title}</span>
                </button>
                <div className="note-card">
                  <h4>{plan.title}</h4>
                  <NoteList
                    tasks={plan.tasks}
                    onToggle={(id) => void window.todothings.dockToggleTask(id)}
                  />
                </div>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

function NoteList({
  tasks,
  onToggle,
}: {
  tasks: DockTask[]
  onToggle: (id: string) => void
}) {
  const roots = tasks.filter((task) => task.parentId == null)
  if (roots.length === 0) {
    return <p className="note-empty">暂无待办</p>
  }
  return (
    <ul className="note-list">
      {roots.map((task) => {
        const children = tasks.filter((child) => child.parentId === task.id)
        return (
          <li key={task.id} className={task.completed ? 'done' : ''}>
            <label>
              <input
                type="checkbox"
                checked={task.completed}
                onChange={() => onToggle(task.id)}
              />
              <span>{task.title}</span>
            </label>
            {children.length > 0 && (
              <ul>
                {children.map((child) => (
                  <li key={child.id} className={child.completed ? 'done' : ''}>
                    <label>
                      <input
                        type="checkbox"
                        checked={child.completed}
                        onChange={() => onToggle(child.id)}
                      />
                      <span>{child.title}</span>
                    </label>
                  </li>
                ))}
              </ul>
            )}
          </li>
        )
      })}
    </ul>
  )
}
