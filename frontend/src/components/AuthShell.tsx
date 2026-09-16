import type { ReactNode } from 'react'

export default function AuthShell({
  title,
  subtitle,
  children,
}: {
  title: string
  subtitle?: string
  children: ReactNode
}) {
  return (
    <div className="flex min-h-screen items-center justify-center overflow-hidden bg-surface px-4">
      <div className="relative w-full max-w-sm">
        <div className="mb-8 text-center">
          <h1 className="text-3xl text-ink">{title}</h1>
          {subtitle && <p className="mt-2 text-sm text-ink-soft">{subtitle}</p>}
        </div>
        <div className="card p-6">{children}</div>
      </div>
    </div>
  )
}
