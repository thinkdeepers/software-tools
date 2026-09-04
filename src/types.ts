export type Priority = 1 | 2 | 3 | 4
export type ThemeId = 'white' | 'black' | 'colorful'
export type PlanFilterId = 'all' | string
export type FontSizeId = 'small' | 'medium' | 'large'
export type FontFamilyId = 'yahei' | 'youyuan' | 'fangsong' | 'kaiti'

export const FONT_FAMILY_OPTIONS: { id: FontFamilyId; label: string }[] = [
  { id: 'yahei', label: '微软雅黑' },
  { id: 'youyuan', label: '幼圆加粗' },
  { id: 'fangsong', label: '仿宋' },
  { id: 'kaiti', label: '楷体' },
]

export type DockEdge = 'left' | 'right' | 'top' | 'bottom'

export interface Plan {
  id: string
  title: string
  notes: string
  color: string
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

export const PRIORITY_META: Record<
  Priority,
  { label: string; color: string; bg: string }
> = {
  1: { label: '紧急', color: '#c23b3b', bg: 'rgba(194, 59, 59, 0.12)' },
  2: { label: '重要', color: '#d17a1f', bg: 'rgba(209, 122, 31, 0.12)' },
  3: { label: '普通', color: '#2a6f97', bg: 'rgba(42, 111, 151, 0.12)' },
  4: { label: '较低', color: '#6b7280', bg: 'rgba(107, 114, 128, 0.12)' },
}

export interface EdgeDockUiState {
  enabled: boolean
  collapsed: boolean
  edge: DockEdge | null
}

export const ALL_PLANS_ID = 'all' as const
