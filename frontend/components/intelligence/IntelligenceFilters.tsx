"use client";
// /intelligence 各 Panel 共享件：过滤控件（label+input/select/checkbox）、双空态、时间格式化。
// 双空态口径照 research 页（research-empty / research-empty-filtered 先例）：
// - intel-empty-filtered：有过滤条件无结果（附清除过滤）；
// - intel-empty：全空引导（文案 + 去行情台 CTA）。

import Link from "next/link";
import type { InputHTMLAttributes } from "react";

const inputCls =
  "w-32 rounded-md border border-[color:var(--color-line)] bg-transparent px-2.5 py-1.5 text-sm text-[color:var(--color-ink)]";

/** 标签 + 文本/日期输入（type 经 inputProps 传入，date 同款宽度）。 */
export function LabeledInput({
  label,
  inputProps,
}: {
  label: string;
  inputProps: InputHTMLAttributes<HTMLInputElement>;
}) {
  return (
    <label className="flex items-center gap-2">
      <span className="shrink-0 text-xs text-[color:var(--color-ink-faint)]">{label}</span>
      <input aria-label={label} className={inputCls} {...inputProps} />
    </label>
  );
}

/** 标签 + 下拉（options 为 value/文案对；value 空串 = 全部；泛型接枚举联合态）。 */
export function LabeledSelect<T extends string>({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: T;
  onChange: (value: T) => void;
  options: Array<{ value: T; label: string }>;
}) {
  return (
    <label className="flex items-center gap-2">
      <span className="shrink-0 text-xs text-[color:var(--color-ink-faint)]">{label}</span>
      <select
        aria-label={label}
        className={inputCls}
        value={value}
        onChange={(e) => onChange(e.target.value as T)} // 可选值即 options 的 T 集合，DOM string 收窄安全
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  );
}

/** 标签 + checkbox（如「仅重大公告」）。 */
export function LabeledCheckbox({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className="flex items-center gap-1.5 text-xs text-[color:var(--color-ink-dim)]">
      <input
        type="checkbox"
        aria-label={label}
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      {label}
    </label>
  );
}

/** Instant（UTC ISO）→ 上海时区 MM-DD HH:mm（与后端日界折算口径一致）；非法值原样返回。 */
export function formatPublished(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

/** 过滤无结果空态（data-testid=intel-empty-filtered）：清除过滤回到无条件检索。 */
export function IntelEmptyFiltered({ onClear }: { onClear: () => void }) {
  return (
    <div
      className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-6 text-sm text-[color:var(--color-ink-faint)]"
      data-testid="intel-empty-filtered"
    >
      没有符合条件的情报
      <button
        type="button"
        className="ml-3 rounded-md border border-[color:var(--color-line)] px-2.5 py-1 text-xs text-[color:var(--color-ink-dim)] hover:border-[color:var(--color-ink-faint)]"
        onClick={onClear}
      >
        清除过滤
      </button>
    </div>
  );
}

/** 全空引导空态（data-testid=intel-empty）：文案 + 去行情台 CTA。 */
export function IntelEmptyGuide() {
  return (
    <div
      className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-8 text-center space-y-3"
      data-testid="intel-empty"
    >
      <p className="text-sm text-[color:var(--color-ink-dim)]">
        暂无情报内容——数据台尚未采集到条目，或当前订阅范围暂无命中。
      </p>
      <Link
        href="/market"
        className="inline-block rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm text-[color:var(--color-ink-dim)] hover:border-[color:var(--color-ink-faint)]"
      >
        去行情台
      </Link>
    </div>
  );
}
