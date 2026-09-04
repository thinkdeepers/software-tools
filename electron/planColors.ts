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
