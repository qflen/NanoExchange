import { useEffect, useState } from "react";

type Theme = "dark" | "light";
type Palette = "default" | "cb";

const THEME_KEY = "nx.theme";
const PALETTE_KEY = "nx.palette";

function readInitial<T extends string>(key: string, allowed: T[], fallback: T): T {
  if (typeof localStorage === "undefined") return fallback;
  const raw = localStorage.getItem(key);
  return (allowed as string[]).includes(raw ?? "") ? (raw as T) : fallback;
}

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(() =>
    readInitial<Theme>(THEME_KEY, ["dark", "light"], "dark"),
  );
  const [palette, setPalette] = useState<Palette>(() =>
    readInitial<Palette>(PALETTE_KEY, ["default", "cb"], "default"),
  );

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem(THEME_KEY, theme);
  }, [theme]);
  useEffect(() => {
    document.documentElement.dataset.palette = palette;
    localStorage.setItem(PALETTE_KEY, palette);
  }, [palette]);

  return (
    <div className="flex items-center gap-1 text-[11px]">
      <button
        type="button"
        onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
        title={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}
        className="px-2 py-0.5 rounded border border-panel-border text-neutral-fg/70 hover:text-neutral-fg"
      >
        {theme === "dark" ? "dark" : "light"}
      </button>
      <button
        type="button"
        onClick={() => setPalette(palette === "default" ? "cb" : "default")}
        title="Toggle colorblind-friendly palette"
        className={
          "px-2 py-0.5 rounded border text-[11px] " +
          (palette === "cb"
            ? "border-highlight text-highlight"
            : "border-panel-border text-neutral-fg/70 hover:text-neutral-fg")
        }
      >
        cb
      </button>
    </div>
  );
}
