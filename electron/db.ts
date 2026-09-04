import initSqlJs, { type Database, type SqlJsStatic } from 'sql.js'
import { app } from 'electron'
import fs from 'node:fs'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { normalizePlanColor, pickPlanColor } from './planColors'
import type {
  CreatePlanInput,
  CreateTaskInput,
  Plan,
  Priority,
  Task,
  UpdatePlanInput,
  UpdateTaskInput,
} from './types'

let SQL: SqlJsStatic | null = null
let db: Database | null = null
let persistTimer: NodeJS.Timeout | null = null

function nowIso() {
  return new Date().toISOString()
}

export function getDbPath() {
  const dir = app.getPath('userData')
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true })
  }
  return path.join(dir, 'todothings.db')
}

function wasmPath() {
  const candidates = [
    path.join(__dirname, '../node_modules/sql.js/dist/sql-wasm.wasm'),
    path.join(process.cwd(), 'node_modules/sql.js/dist/sql-wasm.wasm'),
    path.join(app.getAppPath(), 'node_modules/sql.js/dist/sql-wasm.wasm'),
    path.join(app.getAppPath(), 'node_modules/sql.js/dist/sql-wasm.wasm'.replace(/\//g, path.sep)),
  ]
  for (const file of candidates) {
    if (fs.existsSync(file)) return file
  }
  // asar unpack fallback
  const unpacked = candidates.map((f) => f.replace('app.asar', 'app.asar.unpacked'))
  for (const file of unpacked) {
    if (fs.existsSync(file)) return file
  }
  throw new Error('sql-wasm.wasm not found')
}

function schedulePersist() {
  if (!db) return
  if (persistTimer) clearTimeout(persistTimer)
  persistTimer = setTimeout(() => {
    try {
      if (!db) return
      const data = db.export()
      fs.writeFileSync(getDbPath(), Buffer.from(data))
    } catch (err) {
      console.error('persist db failed', err)
    }
  }, 120)
}

function persistNow() {
  if (!db) return
  if (persistTimer) {
    clearTimeout(persistTimer)
    persistTimer = null
  }
  const data = db.export()
  fs.writeFileSync(getDbPath(), Buffer.from(data))
}

export async function initDb() {
  if (db) return db
  SQL = await initSqlJs({
    locateFile: () => wasmPath(),
  })
  const file = getDbPath()
  if (fs.existsSync(file)) {
    const buf = fs.readFileSync(file)
    db = new SQL.Database(buf)
  } else {
    db = new SQL.Database()
  }

  db.run(`
    CREATE TABLE IF NOT EXISTS plans (
      id TEXT PRIMARY KEY,
      title TEXT NOT NULL,
      notes TEXT NOT NULL DEFAULT '',
      color TEXT NOT NULL DEFAULT 'yellow',
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS tasks (
      id TEXT PRIMARY KEY,
      plan_id TEXT NOT NULL,
      parent_id TEXT,
      title TEXT NOT NULL,
      priority INTEGER NOT NULL DEFAULT 4,
      completed INTEGER NOT NULL DEFAULT 0,
      due_at TEXT,
      remind_enabled INTEGER NOT NULL DEFAULT 0,
      reminded_at TEXT,
      sort_order INTEGER NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_tasks_plan ON tasks(plan_id);
    CREATE INDEX IF NOT EXISTS idx_tasks_parent ON tasks(parent_id);
    CREATE INDEX IF NOT EXISTS idx_tasks_remind ON tasks(remind_enabled, due_at, completed);
  `)
  ensurePlanColorColumn()
  schedulePersist()
  return db
}

function tableHasColumn(table: string, column: string): boolean {
  return queryAll(`PRAGMA table_info(${table})`).some((row) => String(row.name) === column)
}

function ensurePlanColorColumn() {
  if (!tableHasColumn('plans', 'color')) {
    requireDb().run("ALTER TABLE plans ADD COLUMN color TEXT NOT NULL DEFAULT 'yellow'")
    const rows = queryAll('SELECT id FROM plans ORDER BY created_at ASC')
    const used: string[] = []
    for (const row of rows) {
      const color = pickPlanColor(used)
      used.push(color)
      requireDb().run('UPDATE plans SET color = ? WHERE id = ?', [color, String(row.id)])
    }
    return
  }
  for (const row of queryAll('SELECT id, color FROM plans')) {
    const raw = row.color == null ? '' : String(row.color)
    const color = normalizePlanColor(raw || null)
    if (color !== raw) {
      requireDb().run('UPDATE plans SET color = ? WHERE id = ?', [color, String(row.id)])
    }
  }
}

function requireDb(): Database {
  if (!db) throw new Error('Database not initialized')
  return db
}

function mapPlan(row: Record<string, unknown>): Plan {
  return {
    id: String(row.id),
    title: String(row.title),
    notes: String(row.notes ?? ''),
    color: normalizePlanColor(row.color == null ? null : String(row.color)),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
  }
}

function mapTask(row: Record<string, unknown>): Task {
  return {
    id: String(row.id),
    planId: String(row.plan_id),
    parentId: row.parent_id == null ? null : String(row.parent_id),
    title: String(row.title),
    priority: Number(row.priority) as Priority,
    completed: Boolean(row.completed),
    dueAt: row.due_at == null ? null : String(row.due_at),
    remindEnabled: Boolean(row.remind_enabled),
    remindedAt: row.reminded_at == null ? null : String(row.reminded_at),
    sortOrder: Number(row.sort_order),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
  }
}

function queryAll(sql: string, params: unknown[] = []): Record<string, unknown>[] {
  const database = requireDb()
  const stmt = database.prepare(sql)
  stmt.bind(params as never[])
  const rows: Record<string, unknown>[] = []
  while (stmt.step()) {
    rows.push(stmt.getAsObject() as Record<string, unknown>)
  }
  stmt.free()
  return rows
}

function queryOne(sql: string, params: unknown[] = []): Record<string, unknown> | null {
  return queryAll(sql, params)[0] ?? null
}

function run(sql: string, params: unknown[] = []) {
  requireDb().run(sql, params as never[])
  schedulePersist()
}

export function listPlans(): Plan[] {
  return queryAll('SELECT * FROM plans ORDER BY updated_at DESC').map(mapPlan)
}

export function createPlan(input: CreatePlanInput): Plan {
  const id = randomUUID()
  const ts = nowIso()
  const color =
    input.color !== undefined
      ? normalizePlanColor(input.color)
      : pickPlanColor(listPlans().map((p) => p.color))
  run(
    `INSERT INTO plans (id, title, notes, color, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
    [id, input.title.trim() || '未命名计划', input.notes?.trim() ?? '', color, ts, ts],
  )
  return getPlan(id)!
}

export function getPlan(id: string): Plan | null {
  const row = queryOne('SELECT * FROM plans WHERE id = ?', [id])
  return row ? mapPlan(row) : null
}

export function updatePlan(input: UpdatePlanInput): Plan | null {
  const existing = getPlan(input.id)
  if (!existing) return null
  const title = input.title !== undefined ? input.title.trim() || existing.title : existing.title
  const notes = input.notes !== undefined ? input.notes : existing.notes
  const color = input.color !== undefined ? normalizePlanColor(input.color) : existing.color
  const ts = nowIso()
  run('UPDATE plans SET title = ?, notes = ?, color = ?, updated_at = ? WHERE id = ?', [
    title,
    notes,
    color,
    ts,
    input.id,
  ])
  return getPlan(input.id)
}

export function deletePlan(id: string): boolean {
  const existing = getPlan(id)
  if (!existing) return false
  run('DELETE FROM tasks WHERE plan_id = ?', [id])
  run('DELETE FROM plans WHERE id = ?', [id])
  return true
}

export function listTasks(planId: string): Task[] {
  return queryAll(
    `SELECT * FROM tasks
     WHERE plan_id = ?
     ORDER BY completed ASC, priority ASC, sort_order ASC, created_at ASC`,
    [planId],
  ).map(mapTask)
}

export function listAllTasks(): Task[] {
  return queryAll(
    `SELECT * FROM tasks
     ORDER BY completed ASC, priority ASC, sort_order ASC, created_at ASC`,
  ).map(mapTask)
}

export function getTask(id: string): Task | null {
  const row = queryOne('SELECT * FROM tasks WHERE id = ?', [id])
  return row ? mapTask(row) : null
}

export function createTask(input: CreateTaskInput): Task {
  const id = randomUUID()
  const ts = nowIso()
  const parentId = input.parentId ?? null
  const priority = input.priority ?? 4
  const dueAt = input.dueAt ?? null
  const remindEnabled = dueAt ? Boolean(input.remindEnabled) : false

  const maxRow = queryOne(
    `SELECT COALESCE(MAX(sort_order), 0) AS m FROM tasks
     WHERE plan_id = ? AND IFNULL(parent_id, '') = IFNULL(?, '')`,
    [input.planId, parentId],
  )
  const nextOrder = Number(maxRow?.m ?? 0) + 1

  run(
    `INSERT INTO tasks (
      id, plan_id, parent_id, title, priority, completed,
      due_at, remind_enabled, reminded_at, sort_order, created_at, updated_at
    ) VALUES (?, ?, ?, ?, ?, 0, ?, ?, NULL, ?, ?, ?)`,
    [
      id,
      input.planId,
      parentId,
      input.title.trim() || '新待办',
      priority,
      dueAt,
      remindEnabled ? 1 : 0,
      nextOrder,
      ts,
      ts,
    ],
  )
  run('UPDATE plans SET updated_at = ? WHERE id = ?', [ts, input.planId])
  return getTask(id)!
}

export function updateTask(input: UpdateTaskInput): Task | null {
  const existing = getTask(input.id)
  if (!existing) return null

  const title = input.title !== undefined ? input.title.trim() || existing.title : existing.title
  const priority = input.priority ?? existing.priority
  const completed = input.completed ?? existing.completed
  let dueAt = input.dueAt !== undefined ? input.dueAt : existing.dueAt
  let remindEnabled =
    input.remindEnabled !== undefined ? input.remindEnabled : existing.remindEnabled
  if (!dueAt) remindEnabled = false
  const remindedAt =
    input.remindedAt !== undefined ? input.remindedAt : existing.remindedAt
  const sortOrder = input.sortOrder ?? existing.sortOrder
  const parentId = input.parentId !== undefined ? input.parentId : existing.parentId
  const ts = nowIso()

  run(
    `UPDATE tasks SET
      title = ?, priority = ?, completed = ?, due_at = ?,
      remind_enabled = ?, reminded_at = ?, sort_order = ?, parent_id = ?, updated_at = ?
     WHERE id = ?`,
    [
      title,
      priority,
      completed ? 1 : 0,
      dueAt,
      remindEnabled ? 1 : 0,
      remindedAt,
      sortOrder,
      parentId,
      ts,
      input.id,
    ],
  )
  run('UPDATE plans SET updated_at = ? WHERE id = ?', [ts, existing.planId])
  return getTask(input.id)
}

function collectDescendantIds(rootId: string): string[] {
  const all = queryAll('SELECT id, parent_id FROM tasks')
  const byParent = new Map<string, string[]>()
  for (const row of all) {
    const pid = row.parent_id == null ? '' : String(row.parent_id)
    const list = byParent.get(pid) ?? []
    list.push(String(row.id))
    byParent.set(pid, list)
  }
  const result: string[] = []
  const walk = (id: string) => {
    result.push(id)
    for (const child of byParent.get(id) ?? []) walk(child)
  }
  walk(rootId)
  return result
}

export function deleteTask(id: string): boolean {
  const existing = getTask(id)
  if (!existing) return false
  const ids = collectDescendantIds(id)
  for (const taskId of ids.reverse()) {
    run('DELETE FROM tasks WHERE id = ?', [taskId])
  }
  run('UPDATE plans SET updated_at = ? WHERE id = ?', [nowIso(), existing.planId])
  return true
}

export function listDueReminders(nowMs: number): Task[] {
  return queryAll(
    `SELECT * FROM tasks
     WHERE completed = 0
       AND remind_enabled = 1
       AND due_at IS NOT NULL
       AND reminded_at IS NULL
       AND due_at <= ?`,
    [new Date(nowMs).toISOString()],
  ).map(mapTask)
}

export function countTodayOpenTasks(): number {
  const start = new Date()
  start.setHours(0, 0, 0, 0)
  const end = new Date()
  end.setHours(23, 59, 59, 999)
  const row = queryOne(
    `SELECT COUNT(*) AS c FROM tasks
     WHERE completed = 0
       AND due_at IS NOT NULL
       AND due_at >= ?
       AND due_at <= ?`,
    [start.toISOString(), end.toISOString()],
  )
  return Number(row?.c ?? 0)
}

export function closeDb() {
  if (persistTimer) {
    clearTimeout(persistTimer)
    persistTimer = null
  }
  if (db) {
    persistNow()
    db.close()
    db = null
  }
}
