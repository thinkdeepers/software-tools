import { useEffect, useState } from 'react'
import { normalizePlanColor } from '../planColors'
import type { DockEdge } from '../types'

type DockPlan = { id: string; title: string; color: string }

type DockViewState = {
  edge: DockEdge
  fanned: boolean
  plans: DockPlan[]
  selectedId: string | null
  overflowFrom: number
  windowSize: { width: number; height: number }
  visual: { x: number; y: number; width: number; height: number }
}

const MAX_VISIBLE = 8

export function DockApp() {
  const [state, setState] = useState<DockViewState | null>(null)

  useEffect(() => {
    document.documentElement.classList.add('dock-root')
    const off = window.todothings.onDockState((next) => setState(next))
    void window.todothings.dockReady()
    return () => {
      off()
      document.documentElement.classList.remove('dock-root')
    }
  }, [])

  if (!state) return null

  const items = state.plans
  const start = items.length === 0 ? 0 : state.overflowFrom % Math.max(items.length, 1)
  const visible = items.length === 0 ? [] : rotate(items, start).slice(0, MAX_VISIBLE)
  const hiddenCount = Math.max(0, items.length - visible.length)

  return (
    <div className="deck-stage" style={{ width: '100%', height: '100%' }}>
      <div
        className={`deck edge-${state.edge} ${state.fanned ? 'fanned' : 'sleeping'}`}
        style={{
          left: state.visual.x,
          top: state.visual.y,
          width: state.visual.width,
          height: state.visual.height,
        }}
        onMouseEnter={() => window.todothings.dockPointer(true)}
        onMouseLeave={() => window.todothings.dockPointer(false)}
        onContextMenu={(event) => {
          event.preventDefault()
          window.todothings.dockContextMenu()
        }}
      >
        <div className="pill">
          {visible.length === 0 && (
            <button
              type="button"
              className="tab color-paper"
              title="打开 TodoThings"
              onClick={() => window.todothings.dockSelectPlan('all')}
            >
              <span className="dash" />
              <span className="label">所有计划</span>
            </button>
          )}
          {visible.map((plan, index) => (
            <button
              key={plan.id}
              type="button"
              className={`tab color-${normalizePlanColor(plan.color)}${state.selectedId === plan.id ? ' selected' : ''}`}
              style={{
                animationDelay: state.fanned ? `${index * 45}ms` : '0ms',
                ['--lean' as string]: state.fanned ? `${index % 2 === 0 ? -1.2 : 1.1}deg` : '0deg',
              }}
              title={plan.title}
              onClick={() => window.todothings.dockSelectPlan(plan.id)}
            >
              <span className="dash" />
              <span className="label">{plan.title}</span>
            </button>
          ))}
          {hiddenCount > 0 && (
            <button
              type="button"
              className="tab more color-paper"
              style={{
                animationDelay: state.fanned ? `${visible.length * 45}ms` : '0ms',
              }}
              title={`还有 ${hiddenCount} 个计划`}
              onClick={() => window.todothings.dockShowMore()}
            >
              <span className="dash" />
              <span className="label">+{hiddenCount}</span>
            </button>
          )}
          {state.fanned && (
            <button
              type="button"
              className="tab create color-paper"
              style={{
                animationDelay: `${(visible.length + (hiddenCount > 0 ? 1 : 0)) * 45}ms`,
              }}
              title="新建计划"
              onClick={() => window.todothings.dockCreatePlan()}
            >
              <span className="dash" />
              <span className="plus">+</span>
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

function rotate<T>(list: T[], start: number): T[] {
  if (list.length === 0) return list
  const i = ((start % list.length) + list.length) % list.length
  return list.slice(i).concat(list.slice(0, i))
}
