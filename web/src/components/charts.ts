import { useEffect, useState } from "react";

/** Vibrant series colours; readable on both light and dark surfaces. */
export const PALETTE = ["#2F6FE4", "#F2A531", "#14A38B", "#E0559A", "#7B5CE6", "#E14D4D", "#3DB8E8", "#9BC53D"];

export const TONE_COLOURS: Record<string, string> = {
  teal: "#14A38B",
  blue: "#2F6FE4",
  violet: "#7B5CE6",
  amber: "#F2A531",
  orange: "#F07A2B",
  red: "#E14D4D",
  slate: "#7C8AA5",
  pink: "#E0559A",
};

export type ChartTheme = { text: string; muted: string; grid: string; surface: string; border: string };

function read(): ChartTheme {
  const css = getComputedStyle(document.documentElement);
  const v = (name: string) => css.getPropertyValue(name).trim();
  return { text: v("--text"), muted: v("--muted"), grid: v("--grid"), surface: v("--surface"), border: v("--border") };
}

/** SVG attributes can't use var(), so charts read the resolved theme colours and follow light/dark changes. */
export function useChartTheme(): ChartTheme {
  const [theme, setTheme] = useState<ChartTheme>(read);
  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const update = () => setTheme(read());
    mq.addEventListener("change", update);
    return () => mq.removeEventListener("change", update);
  }, []);
  return theme;
}

export function tooltipStyle(t: ChartTheme) {
  return {
    contentStyle: {
      background: t.surface,
      border: `1px solid ${t.border}`,
      borderRadius: 12,
      color: t.text,
      boxShadow: "0 8px 24px rgba(10, 30, 70, 0.18)",
      fontFamily: "Figtree, system-ui, sans-serif",
      fontSize: 13,
    },
    labelStyle: { color: t.text, fontWeight: 600, marginBottom: 4 },
    itemStyle: { color: t.text },
  };
}
