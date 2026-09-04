import { useEffect, useRef, useState } from 'react'
import { PLAN_COLOR_IDS, PLAN_COLOR_META, type PlanColorId } from '../planColors'

interface Props {
  open: boolean
  title: string
  label: string
  initialValue: string
  confirmText?: string
  color?: PlanColorId
  onColorChange?: (color: PlanColorId) => void
  onCancel: () => void
  onConfirm: (value: string) => void
}

export function PlanDialog({
  open,
  title,
  label,
  initialValue,
  confirmText = '确定',
  color,
  onColorChange,
  onCancel,
  onConfirm,
}: Props) {
  const [value, setValue] = useState(initialValue)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (open) {
      setValue(initialValue)
      requestAnimationFrame(() => {
        inputRef.current?.focus()
        inputRef.current?.select()
      })
    }
  }, [open, initialValue])

  if (!open) return null

  return (
    <div className="dialog-backdrop" onMouseDown={onCancel}>
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h3>{title}</h3>
        <label>
          <span>{label}</span>
          <input
            ref={inputRef}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') onConfirm(value.trim() || initialValue || '新计划')
              if (e.key === 'Escape') onCancel()
            }}
          />
        </label>
        {color && onColorChange && (
          <div className="color-field">
            <span>停靠颜色</span>
            <div className="color-swatches" role="listbox" aria-label="计划颜色">
              {PLAN_COLOR_IDS.map((id) => (
                <button
                  key={id}
                  type="button"
                  className={`color-swatch${color === id ? ' selected' : ''}`}
                  style={{ background: PLAN_COLOR_META[id].hex }}
                  title={PLAN_COLOR_META[id].label}
                  aria-label={PLAN_COLOR_META[id].label}
                  onClick={() => onColorChange(id)}
                />
              ))}
            </div>
          </div>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn ghost" onClick={onCancel}>
            取消
          </button>
          <button
            type="button"
            className="btn"
            onClick={() => onConfirm(value.trim() || initialValue || '新计划')}
          >
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  )
}

interface ConfirmProps {
  open: boolean
  title: string
  message: string
  onCancel: () => void
  onConfirm: () => void
}

export function ConfirmDialog({ open, title, message, onCancel, onConfirm }: ConfirmProps) {
  if (!open) return null

  return (
    <div className="dialog-backdrop" onMouseDown={onCancel}>
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h3>{title}</h3>
        <p className="dialog-message">{message}</p>
        <div className="dialog-actions">
          <button type="button" className="btn ghost" onClick={onCancel}>
            取消
          </button>
          <button type="button" className="btn danger-solid" onClick={onConfirm}>
            删除
          </button>
        </div>
      </div>
    </div>
  )
}
