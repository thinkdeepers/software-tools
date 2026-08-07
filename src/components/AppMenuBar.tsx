import { useEffect, useRef, useState } from 'react'
import type { FontFamilyId, FontSizeId, Plan, PlanFilterId, ThemeId } from '../types'
import { ALL_PLANS_ID, FONT_FAMILY_OPTIONS } from '../types'

function fontPreview(id: FontFamilyId): string {
  switch (id) {
    case 'yahei':
      return '"Microsoft YaHei", "微软雅黑", sans-serif'
    case 'youyuan':
      return '"YouYuan", "幼圆", sans-serif'
    case 'fangsong':
      return '"FangSong", "仿宋", serif'
    case 'kaiti':
      return '"KaiTi", "楷体", serif'
  }
}

type MenuKey = 'plan' | 'theme' | 'pin' | 'edit' | null

interface Props {
  plans: Plan[]
  planFilter: PlanFilterId
  theme: ThemeId
  alwaysOnTop: boolean
  edgeDock: boolean
  showCompleted: boolean
  fontSize: FontSizeId
  fontFamily: FontFamilyId
  maximized: boolean
  onSelectPlan: (id: PlanFilterId) => void
  onCreatePlan: () => void
  onRenamePlan: () => void
  onDeletePlan: () => void
  onTheme: (theme: ThemeId) => void
  onTogglePin: () => void
  onToggleEdgeDock: () => void
  onShowCompleted: (show: boolean) => void
  onFontSize: (size: FontSizeId) => void
  onFontFamily: (family: FontFamilyId) => void
}

export function AppMenuBar({
  plans,
  planFilter,
  theme,
  alwaysOnTop,
  edgeDock,
  showCompleted,
  fontSize,
  fontFamily,
  maximized,
  onSelectPlan,
  onCreatePlan,
  onRenamePlan,
  onDeletePlan,
  onTheme,
  onTogglePin,
  onToggleEdgeDock,
  onShowCompleted,
  onFontSize,
  onFontFamily,
}: Props) {
  const [open, setOpen] = useState<MenuKey>(null)
  const rootRef = useRef<HTMLElement>(null)

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(null)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  const canEditPlan = planFilter !== ALL_PLANS_ID && plans.some((p) => p.id === planFilter)

  return (
    <header className="chrome" ref={rootRef}>
      <div className="chrome-drag">
        <span className="chrome-brand">TodoThings</span>
        <nav className="chrome-menus">
          <div className={`menu-item${open === 'plan' ? ' open' : ''}`}>
            <button type="button" className="menu-btn" onClick={() => setOpen(open === 'plan' ? null : 'plan')}>
              计划
            </button>
            {open === 'plan' && (
              <div className="menu-dropdown">
                <button
                  type="button"
                  className="menu-option"
                  onClick={() => {
                    onSelectPlan(ALL_PLANS_ID)
                    setOpen(null)
                  }}
                >
                  <span className="check">{planFilter === ALL_PLANS_ID ? '●' : '○'}</span>
                  所有计划
                </button>
                {plans.map((plan) => (
                  <button
                    key={plan.id}
                    type="button"
                    className="menu-option"
                    onClick={() => {
                      onSelectPlan(plan.id)
                      setOpen(null)
                    }}
                  >
                    <span className="check">{planFilter === plan.id ? '●' : '○'}</span>
                    {plan.title}
                  </button>
                ))}
                <div className="menu-sep" />
                <button
                  type="button"
                  className="menu-option"
                  onClick={() => {
                    onCreatePlan()
                    setOpen(null)
                  }}
                >
                  <span className="check" />
                  新建计划
                </button>
                <button
                  type="button"
                  className="menu-option"
                  disabled={!canEditPlan}
                  onClick={() => {
                    onRenamePlan()
                    setOpen(null)
                  }}
                >
                  <span className="check" />
                  重命名当前计划
                </button>
                <button
                  type="button"
                  className="menu-option danger"
                  disabled={!canEditPlan}
                  onClick={() => {
                    onDeletePlan()
                    setOpen(null)
                  }}
                >
                  <span className="check" />
                  删除当前计划
                </button>
              </div>
            )}
          </div>

          <div className={`menu-item${open === 'theme' ? ' open' : ''}`}>
            <button type="button" className="menu-btn" onClick={() => setOpen(open === 'theme' ? null : 'theme')}>
              主题
            </button>
            {open === 'theme' && (
              <div className="menu-dropdown">
                {(
                  [
                    ['white', '白色'],
                    ['black', '黑色'],
                    ['colorful', '多彩色'],
                  ] as const
                ).map(([id, label]) => (
                  <button
                    key={id}
                    type="button"
                    className="menu-option"
                    onClick={() => {
                      onTheme(id)
                      setOpen(null)
                    }}
                  >
                    <span className="check">{theme === id ? '●' : '○'}</span>
                    {label}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className={`menu-item${open === 'pin' ? ' open' : ''}`}>
            <button type="button" className="menu-btn" onClick={() => setOpen(open === 'pin' ? null : 'pin')}>
              置顶
            </button>
            {open === 'pin' && (
              <div className="menu-dropdown">
                <button
                  type="button"
                  className="menu-option"
                  onClick={() => {
                    onTogglePin()
                    setOpen(null)
                  }}
                >
                  <span className="check">{alwaysOnTop ? '✓' : ''}</span>
                  始终置顶
                </button>
                <button
                  type="button"
                  className="menu-option"
                  onClick={() => {
                    onToggleEdgeDock()
                    setOpen(null)
                  }}
                >
                  <span className="check">{edgeDock ? '✓' : ''}</span>
                  侧边停靠
                </button>
              </div>
            )}
          </div>

          <div className={`menu-item${open === 'edit' ? ' open' : ''}`}>
            <button type="button" className="menu-btn" onClick={() => setOpen(open === 'edit' ? null : 'edit')}>
              编辑
            </button>
            {open === 'edit' && (
              <div className="menu-dropdown">
                <button
                  type="button"
                  className="menu-option"
                  onClick={() => {
                    onShowCompleted(true)
                    setOpen(null)
                  }}
                >
                  <span className="check">{showCompleted ? '●' : '○'}</span>
                  显示已完成
                </button>
                <button
                  type="button"
                  className="menu-option"
                  onClick={() => {
                    onShowCompleted(false)
                    setOpen(null)
                  }}
                >
                  <span className="check">{!showCompleted ? '●' : '○'}</span>
                  不显示已完成
                </button>
                <div className="menu-sep" />
                {(
                  [
                    ['small', '字体小'],
                    ['medium', '字体中'],
                    ['large', '字体大'],
                  ] as const
                ).map(([id, label]) => (
                  <button
                    key={id}
                    type="button"
                    className="menu-option"
                    onClick={() => {
                      onFontSize(id)
                      setOpen(null)
                    }}
                  >
                    <span className="check">{fontSize === id ? '●' : '○'}</span>
                    {label}
                  </button>
                ))}
                <div className="menu-sep" />
                {FONT_FAMILY_OPTIONS.map(({ id, label }) => (
                  <button
                    key={id}
                    type="button"
                    className="menu-option"
                    style={{ fontFamily: fontPreview(id) }}
                    onClick={() => {
                      onFontFamily(id)
                      setOpen(null)
                    }}
                  >
                    <span className="check">{fontFamily === id ? '●' : '○'}</span>
                    {label}
                  </button>
                ))}
              </div>
            )}
          </div>
        </nav>
      </div>

      <div className="chrome-controls">
        <button type="button" title="最小化" onClick={() => void window.todothings.windowMinimize()}>
          ─
        </button>
        <button
          type="button"
          title={maximized ? '还原' : '最大化'}
          onClick={() => void window.todothings.windowMaximizeToggle()}
        >
          {maximized ? '❐' : '□'}
        </button>
        <button
          type="button"
          className="close"
          title="关闭"
          onClick={() => void window.todothings.windowClose()}
        >
          ×
        </button>
      </div>
    </header>
  )
}
