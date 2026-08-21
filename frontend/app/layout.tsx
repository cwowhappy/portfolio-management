import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";
import { Providers } from "./providers";
import { AuthProvider } from "@/lib/auth";
import { AuthNav } from "@/components/auth/AuthNav";

export const metadata: Metadata = {
  title: "砚台 · A股投研助手",
  description: "证券投资分析系统一期 · AI Agent 投研对话服务",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body>
        <Providers>
        <AuthProvider>
        <header className="fixed top-0 left-0 right-0 z-40 border-b border-[color:var(--color-line)] bg-[color:var(--color-bg)]/85 backdrop-blur-sm">
          <div className="mx-auto flex h-12 max-w-[1400px] items-center gap-6 px-5">
            <Link href="/" className="group flex items-center gap-2.5">
              <span className="grid h-6 w-6 place-items-center rounded border border-[color:var(--color-line)] bg-[color:var(--color-panel)] text-[11px] text-[color:var(--color-up)] transition-colors group-hover:border-[color:var(--color-up)]">
                砚
              </span>
              <span className="font-[family-name:var(--font-display)] text-[15px] tracking-wide text-[color:var(--color-ink)]">
                砚台 <span className="text-[color:var(--color-ink-faint)]">· A股投研助手</span>
              </span>
            </Link>
            <nav className="ml-auto flex items-center gap-1 text-[13px]">
              <Link
                href="/"
                className="rounded-md px-3 py-1.5 text-[color:var(--color-ink-dim)] transition-colors hover:bg-[color:var(--color-panel)] hover:text-[color:var(--color-ink)]"
              >
                对话
              </Link>
              <Link
                href="/market"
                className="rounded-md px-3 py-1.5 text-[color:var(--color-ink-dim)] transition-colors hover:bg-[color:var(--color-panel)] hover:text-[color:var(--color-ink)]"
              >
                行情台
              </Link>
              <a
                href="https://github.com"
                target="_blank"
                rel="noreferrer"
                className="rounded-md px-3 py-1.5 text-[color:var(--color-ink-faint)] transition-colors hover:text-[color:var(--color-ink-dim)]"
              >
                一期 v0.1
              </a>
              <span className="mx-1 h-4 w-px bg-[color:var(--color-line)]" aria-hidden="true" />
              <AuthNav />
            </nav>
          </div>
        </header>
        <main className="h-dvh pt-12">{children}</main>
        </AuthProvider>
        </Providers>
      </body>
    </html>
  );
}
