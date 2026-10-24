import type { RefObject } from 'react'

/**
 * 固定宽度的小时/分钟输入：只收数字，填满 2 位自动跳下一个，失焦补零/夹紧。
 */
export function TimeInput({
  value,
  onChange,
  max,
  label,
  onComplete,
  inputRef,
  disabled,
}: {
  value: string
  onChange: (value: string) => void
  max: number
  label: string
  onComplete?: () => void
  inputRef?: RefObject<HTMLInputElement | null>
  disabled?: boolean
}) {
  return (
    <input
      ref={inputRef}
      className="time-input"
      type="text"
      inputMode="numeric"
      maxLength={2}
      aria-label={label}
      value={value}
      disabled={disabled}
      onChange={(event) => {
        const digits = event.target.value.replace(/\D/g, '').slice(0, 2)
        onChange(digits)
        if (digits.length === 2) onComplete?.()
      }}
      onFocus={(event) => event.currentTarget.select()}
      onBlur={() => {
        const number = value === '' ? 0 : Number(value)
        const clamped = Math.min(Math.max(number, 0), max)
        onChange(String(clamped).padStart(2, '0'))
      }}
    />
  )
}
