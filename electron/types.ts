export type Priority = 1 | 2 | 3 | 4
export type ThemeId = 'white' | 'black' | 'colorful'
export type PlanFilterId = 'all' | string
export type FontSizeId = 'small' | 'medium' | 'large'
export type FontFamilyId = 'yahei' | 'youyuan' | 'fangsong' | 'kaiti'

export interface Plan {
  id: string
  title: string
  notes: string
  createdAt: string
  updatedAt: string
}

export interface Task {
  id: string
  planId: string
  parentId: string | null
  title: string
  priority: Priority
  completed: boolean
  dueAt: string | null
  remindEnabled: boolean
  remindedAt: string | null
  sortOrder: number
  createdAt: string
  updatedAt: string
}

export interface CreatePlanInput {
  title: string
  notes?: string
}

export interface UpdatePlanInput {
  id: string
  title?: string
  notes?: string
}

export interface CreateTaskInput {
  planId: string
  parentId?: string | null
  title: string
  priority?: Priority
  dueAt?: string | null
  remindEnabled?: boolean
}

export interface UpdateTaskInput {
  id: string
  title?: string
  priority?: Priority
  completed?: boolean
  dueAt?: string | null
  remindEnabled?: boolean
  remindedAt?: string | null
  sortOrder?: number
  parentId?: string | null
}

export interface AppSettings {
  openAtLogin: boolean
  alwaysOnTop: boolean
  edgeDock: boolean
  theme: ThemeId
  planFilter: PlanFilterId
  showCompleted: boolean
  fontSize: FontSizeId
  fontFamily: FontFamilyId
}
