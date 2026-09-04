export const PLAN_COLOR_IDS = [
  'yellow',
  'peach',
  'pink',
  'lavender',
  'sky',
  'mint',
  'lime',
  'tangerine',
] as const

export type PlanColorId = (typeof PLAN_COLOR_IDS)[number]

export const PLAN_COLOR_META: Record<
  PlanColorId,
  { id: PlanColorId; label: string; hex: string; ink: string }
> = {
  yellow: { id: 'yellow', label: '明黄', hex: '#F5D76E', ink: '#5C4A12' },
  peach: { id: 'peach', label: '蜜桃', hex: '#F4B183', ink: '#6B3210' },
  pink: { id: 'pink', label: '花粉', hex: '#F4A7C3', ink: '#6B2040' },
  lavender: { id: 'lavender', label: '丁香', hex: '#C9B6F0', ink: '#3D2A6B' },
  sky: { id: 'sky', label: '晴空', hex: '#8EC5F0', ink: '#1A3F5C' },
  mint: { id: 'mint', label: '薄荷', hex: '#8FD9B8', ink: '#1A4A35' },
  lime: { id: 'lime', label: '青柠', hex: '#C5E07A', ink: '#3A4A14' },
  tangerine: { id: 'tangerine', label: '柑橘', hex: '#F0A05A', ink: '#5C3A10' },
}

export function isPlanColorId(value: string): value is PlanColorId {
  return (PLAN_COLOR_IDS as readonly string[]).includes(value)
}

export function normalizePlanColor(raw: string | null | undefined): PlanColorId {
  return raw && isPlanColorId(raw) ? raw : 'yellow'
}

export function nextPlanColorId(current: string): PlanColorId {
  const i = PLAN_COLOR_IDS.indexOf(normalizePlanColor(current))
  return PLAN_COLOR_IDS[(i + 1) % PLAN_COLOR_IDS.length]!
}

export function pickPlanColor(used: string[]): PlanColorId {
  const taken = new Set(used.map((c) => normalizePlanColor(c)))
  for (const id of PLAN_COLOR_IDS) {
    if (!taken.has(id)) return id
  }
  return PLAN_COLOR_IDS[used.length % PLAN_COLOR_IDS.length]!
}
