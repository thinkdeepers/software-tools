import { useEffect, useMemo, useRef, useState } from 'react'
import { AppMenuBar } from './components/AppMenuBar'
import { ConfirmDialog, PlanDialog } from './components/PlanDialog'
import { TaskTree } from './components/TaskTree'
import {
  ALL_PLANS_ID,
  type FontFamilyId,
  type FontSizeId,
  type Plan,
  type PlanFilterId,
  type Priority,
  type Task,
  type ThemeId,
} from './types'

function useDebouncedCallback<T extends (...args: never[]) => void>(fn: T, ms: number) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  return (...args: Parameters<T>) => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => fn(...args), ms)
  }
}

type PlanDialogMode = 'create' | 'rename' | null

function applyTheme(theme: ThemeId) {
  document.documentElement.setAttribute('data-theme', theme)
  localStorage.setItem('todothings-theme', theme)
}

function applyFontSize(size: FontSizeId) {
  document.documentElement.setAttribute('data-font', size)
  localStorage.setItem('todothings-font', size)
}

function applyFontFamily(family: FontFamilyId) {
  document.documentElement.setAttribute('data-font-family', family)
  localStorage.setItem('todothings-font-family', family)
}

function loadStoredTheme(): ThemeId | null {
  const raw = localStorage.getItem('todothings-theme')
  if (raw === 'white' || raw === 'black' || raw === 'colorful') return raw
  return null
}

function loadStoredFont(): FontSizeId | null {
  const raw = localStorage.getItem('todothings-font')
  if (raw === 'small' || raw === 'medium' || raw === 'large') return raw
  return null
}

function loadStoredFontFamily(): FontFamilyId | null {
  const raw = localStorage.getItem('todothings-font-family')
  if (raw === 'yahei' || raw === 'youyuan' || raw === 'fangsong' || raw === 'kaiti') return raw
  return null
}

function loadShowCompleted(): boolean {
  const raw = localStorage.getItem('todothings-show-completed')
  if (raw === '0') return false
  return true
}

export default function App() {
  const [plans, setPlans] = useState<Plan[]>([])
  const [planFilter, setPlanFilter] = useState<PlanFilterId>(ALL_PLANS_ID)
  const [tasks, setTasks] = useState<Task[]>([])
  const [newTaskTitle, setNewTaskTitle] = useState('')
  const [loading, setLoading] = useState(true)
  const [planDialog, setPlanDialog] = useState<PlanDialogMode>(null)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [theme, setTheme] = useState<ThemeId>('white')
  const [alwaysOnTop, setAlwaysOnTop] = useState(false)
  const [edgeDock, setEdgeDock] = useState(false)
  const [showCompleted, setShowCompleted] = useState(true)
  const [fontSize, setFontSize] = useState<FontSizeId>('medium')
  const [fontFamily, setFontFamily] = useState<FontFamilyId>('yahei')
  const [maximized, setMaximized] = useState(false)
  const newTaskInputRef = useRef<HTMLInputElement>(null)
  const lastSpecificPlanId = useRef<string | null>(null)

  const activePlan = useMemo(
    () => (planFilter === ALL_PLANS_ID ? null : plans.find((p) => p.id === planFilter) ?? null),
    [plans, planFilter],
  )

  const viewingAll = planFilter === ALL_PLANS_ID

  const visibleTasks = useMemo(() => {
    if (showCompleted) return tasks
    const hidden = new Set(tasks.filter((t) => t.completed).map((t) => t.id))
    return tasks
      .filter((t) => !t.completed)
      .map((t) => ({
        ...t,
        parentId: t.parentId && hidden.has(t.parentId) ? null : t.parentId,
      }))
  }, [tasks, showCompleted])

  async function refreshPlans(preferId?: PlanFilterId | null) {
    const list = await window.todothings.listPlans()
    setPlans(list)
    setPlanFilter((current) => {
      if (preferId === ALL_PLANS_ID) return ALL_PLANS_ID
      if (preferId && preferId !== ALL_PLANS_ID && list.some((p) => p.id === preferId)) {
        return preferId
      }
      if (current === ALL_PLANS_ID) return ALL_PLANS_ID
      if (current && list.some((p) => p.id === current)) return current
      return ALL_PLANS_ID
    })
  }

  async function refreshTasks(filter: PlanFilterId) {
    const list = await window.todothings.listTasks(filter)
    setTasks(list)
  }

  function openCreatePlan() {
    setPlanDialog('create')
  }

  function openRenamePlan() {
    if (!activePlan) return
    setPlanDialog('rename')
  }

  function openDeletePlan() {
    if (!activePlan) return
    setDeleteOpen(true)
  }

  async function handleSelectPlan(id: PlanFilterId) {
    setPlanFilter(id)
    await window.todothings.setPlanFilter(id)
  }

  async function handleTheme(next: ThemeId) {
    setTheme(next)
    applyTheme(next)
    await window.todothings.setTheme(next)
  }

  async function handleShowCompleted(show: boolean) {
    setShowCompleted(show)
    localStorage.setItem('todothings-show-completed', show ? '1' : '0')
    await window.todothings.setShowCompleted(show)
  }

  async function handleFontSize(size: FontSizeId) {
    setFontSize(size)
    applyFontSize(size)
    await window.todothings.setFontSize(size)
  }

  async function handleFontFamily(family: FontFamilyId) {
    setFontFamily(family)
    applyFontFamily(family)
    await window.todothings.setFontFamily(family)
  }

  async function submitPlanDialog(value: string) {
    const title = value.trim() || '新计划'
    if (planDialog === 'create') {
      const plan = await window.todothings.createPlan({ title })
      lastSpecificPlanId.current = plan.id
      await window.todothings.setPlanFilter(plan.id)
      await refreshPlans(plan.id)
    } else if (planDialog === 'rename' && activePlan) {
      if (title !== activePlan.title) {
        const updated = await window.todothings.updatePlan({ id: activePlan.id, title })
        if (updated) {
          setPlans((prev) => prev.map((p) => (p.id === updated.id ? updated : p)))
        }
      }
    }
    setPlanDialog(null)
  }

  async function confirmDeletePlan() {
    if (!activePlan) return
    await window.todothings.deletePlan(activePlan.id)
    setDeleteOpen(false)
    await window.todothings.setPlanFilter(ALL_PLANS_ID)
    await refreshPlans(ALL_PLANS_ID)
  }

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const storedTheme = loadStoredTheme() ?? 'white'
        const storedFont = loadStoredFont() ?? 'medium'
        const storedFamily = loadStoredFontFamily() ?? 'yahei'
        const storedShow = loadShowCompleted()
        applyTheme(storedTheme)
        applyFontSize(storedFont)
        applyFontFamily(storedFamily)
        setTheme(storedTheme)
        setFontSize(storedFont)
        setFontFamily(storedFamily)
        setShowCompleted(storedShow)

        await window.todothings.setTheme(storedTheme)
        await window.todothings.setFontSize(storedFont)
        await window.todothings.setFontFamily(storedFamily)
        await window.todothings.setShowCompleted(storedShow)

        const settings = await window.todothings.getSettings()
        if (!cancelled) {
          setAlwaysOnTop(settings.alwaysOnTop)
          setEdgeDock(settings.edgeDock)
          setPlanFilter(settings.planFilter || ALL_PLANS_ID)
          setMaximized(await window.todothings.windowIsMaximized())
        }
        await refreshPlans(settings.planFilter || ALL_PLANS_ID)
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    void refreshTasks(planFilter)
    if (planFilter !== ALL_PLANS_ID) {
      lastSpecificPlanId.current = planFilter
    }
  }, [planFilter])

  useEffect(() => {
    const offs = [
      window.todothings.onFocusNewTask(() => newTaskInputRef.current?.focus()),
      window.todothings.onCreatePlan(() => openCreatePlan()),
      window.todothings.onTheme((next) => {
        setTheme(next)
        applyTheme(next)
      }),
      window.todothings.onAlwaysOnTop((enabled) => setAlwaysOnTop(enabled)),
      window.todothings.onEdgeDock((enabled) => setEdgeDock(enabled)),
      window.todothings.onShowCompleted((enabled) => setShowCompleted(enabled)),
      window.todothings.onFontSize((size) => {
        setFontSize(size)
        applyFontSize(size)
      }),
      window.todothings.onFontFamily((family) => {
        setFontFamily(family)
        applyFontFamily(family)
      }),
      window.todothings.onWindowState((state) => setMaximized(state.maximized)),
      window.todothings.onReminder(() => {
        void refreshTasks(planFilter)
      }),
    ]
    return () => offs.forEach((off) => off())
  }, [planFilter, activePlan])

  const persistTaskTitle = useDebouncedCallback(async (id: string, title: string) => {
    const updated = await window.todothings.updateTask({ id, title })
    if (updated) {
      setTasks((prev) => prev.map((t) => (t.id === id ? updated : t)))
    }
  }, 350)

  function resolveTargetPlanId(): string | null {
    if (planFilter !== ALL_PLANS_ID) return planFilter
    if (lastSpecificPlanId.current && plans.some((p) => p.id === lastSpecificPlanId.current)) {
      return lastSpecificPlanId.current
    }
    return plans[0]?.id ?? null
  }

  async function handleCreateRootTask() {
    const title = newTaskTitle.trim()
    if (!title) return

    let targetPlanId = resolveTargetPlanId()
    if (!targetPlanId) {
      openCreatePlan()
      return
    }

    await window.todothings.createTask({
      planId: targetPlanId,
      title,
      priority: 3 as Priority,
    })
    setNewTaskTitle('')
    await refreshTasks(planFilter)
  }

  async function patchTask(
    id: string,
    patch: Omit<Parameters<typeof window.todothings.updateTask>[0], 'id'>,
  ) {
    const updated = await window.todothings.updateTask({ ...patch, id })
    if (updated) await refreshTasks(planFilter)
  }

  if (loading) {
    return (
      <div className="app compact">
        <div className="empty-inline">加载中…</div>
      </div>
    )
  }

  const canAddTask = plans.length > 0
  const emptyHint =
    plans.length === 0
      ? '还没有计划，请从菜单「计划 → 新建计划」开始'
      : visibleTasks.length === 0 && tasks.length > 0
        ? '已完成任务已隐藏'
        : viewingAll
          ? '暂无任务'
          : '当前计划暂无任务'

  return (
    <div className="app compact">
      <AppMenuBar
        plans={plans}
        planFilter={planFilter}
        theme={theme}
        alwaysOnTop={alwaysOnTop}
        edgeDock={edgeDock}
        showCompleted={showCompleted}
        fontSize={fontSize}
        fontFamily={fontFamily}
        maximized={maximized}
        onSelectPlan={(id) => void handleSelectPlan(id)}
        onCreatePlan={openCreatePlan}
        onRenamePlan={openRenamePlan}
        onDeletePlan={openDeletePlan}
        onTheme={(t) => void handleTheme(t)}
        onTogglePin={() => {
          const next = !alwaysOnTop
          setAlwaysOnTop(next)
          void window.todothings.setAlwaysOnTop(next)
        }}
        onToggleEdgeDock={() => {
          const next = !edgeDock
          setEdgeDock(next)
          if (next) setAlwaysOnTop(true)
          void window.todothings.setEdgeDock(next)
        }}
        onShowCompleted={(show) => void handleShowCompleted(show)}
        onFontSize={(size) => void handleFontSize(size)}
        onFontFamily={(family) => void handleFontFamily(family)}
      />

      <section className="task-panel">
        {visibleTasks.length === 0 ? (
          <div className="empty-inline">
            {emptyHint}
            {plans.length === 0 && (
              <>
                {' '}
                <button type="button" className="link-btn" onClick={openCreatePlan}>
                  新建计划
                </button>
              </>
            )}
          </div>
        ) : (
          <TaskTree
            tasks={visibleTasks}
            onToggle={(task) => void patchTask(task.id, { completed: !task.completed })}
            onTitleChange={(task, title) => {
              setTasks((prev) => prev.map((t) => (t.id === task.id ? { ...t, title } : t)))
              persistTaskTitle(task.id, title)
            }}
            onPriorityChange={(task, priority) => void patchTask(task.id, { priority })}
            onDueChange={(task, dueAt) =>
              void patchTask(task.id, {
                dueAt,
                remindEnabled: dueAt ? task.remindEnabled : false,
                remindedAt: null,
              })
            }
            onRemindChange={(task, enabled) =>
              void patchTask(task.id, {
                remindEnabled: enabled,
                remindedAt: enabled ? null : task.remindedAt,
              })
            }
            onClearDue={(task) =>
              void patchTask(task.id, {
                dueAt: null,
                remindEnabled: false,
                remindedAt: null,
              })
            }
            onAddSubtask={async (parent) => {
              await window.todothings.createTask({
                planId: parent.planId,
                parentId: parent.id,
                title: '子任务',
                priority: parent.priority,
              })
              await refreshTasks(planFilter)
            }}
            onDelete={async (task) => {
              await window.todothings.deleteTask(task.id)
              await refreshTasks(planFilter)
            }}
          />
        )}
      </section>

      <footer className="add-row">
        <input
          ref={newTaskInputRef}
          value={newTaskTitle}
          onChange={(e) => setNewTaskTitle(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void handleCreateRootTask()
          }}
          placeholder={
            canAddTask
              ? viewingAll
                ? '添加任务到最近使用的计划，回车确认'
                : `添加任务到「${activePlan?.title ?? ''}」，回车确认`
              : '请先新建计划'
          }
          disabled={!canAddTask}
        />
      </footer>

      <PlanDialog
        open={planDialog !== null}
        title={planDialog === 'rename' ? '重命名计划' : '新建计划'}
        label="计划名称"
        initialValue={planDialog === 'rename' ? (activePlan?.title ?? '') : '新计划'}
        confirmText={planDialog === 'rename' ? '保存' : '创建'}
        onCancel={() => setPlanDialog(null)}
        onConfirm={(value) => void submitPlanDialog(value)}
      />

      <ConfirmDialog
        open={deleteOpen}
        title="删除计划"
        message={`确定删除计划「${activePlan?.title ?? ''}」及其全部任务？此操作不可恢复。`}
        onCancel={() => setDeleteOpen(false)}
        onConfirm={() => void confirmDeletePlan()}
      />
    </div>
  )
}
