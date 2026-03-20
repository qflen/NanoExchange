import type { Config } from "tailwindcss";

// Trading palette. CSS variables declared in index.css so dark/light
// themes can override without rebuilding Tailwind classes.
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        "bid-green": "rgb(var(--bid-green) / <alpha-value>)",
        "ask-red": "rgb(var(--ask-red) / <alpha-value>)",
        "neutral-fg": "rgb(var(--neutral) / <alpha-value>)",
        highlight: "rgb(var(--highlight) / <alpha-value>)",
        "panel-bg": "rgb(var(--panel-bg) / <alpha-value>)",
        "panel-border": "rgb(var(--panel-border) / <alpha-value>)",
      },
      fontFamily: {
        mono: ["'JetBrains Mono'", "'Menlo'", "monospace"],
      },
    },
  },
  plugins: [],
} satisfies Config;
