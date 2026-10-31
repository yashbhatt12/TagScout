/**
 * TagScout mark: "TS" in a circle, a red locate dot and three amber signal
 * arcs. Same geometry as TagScoutLogo.kt (viewBox 42 × 36).
 * onDark swaps to the launcher-icon colours (white circle, navy letters).
 */
export function LogoMark({ size = 36, onDark = false }: { size?: number; onDark?: boolean }) {
  const circle = onDark ? "#FFFFFF" : "#1E4B8F";
  const letters = onDark ? "#1E4B8F" : "#FFFFFF";
  return (
    <svg width={(size * 42) / 36} height={size} viewBox="0 0 42 36" role="img" aria-label="TagScout logo">
      <circle cx="15" cy="18" r="13" fill={circle} />
      <g fill="none" stroke="#F2A531" strokeWidth="2" strokeLinecap="round">
        <path d="M31 12.8A6 6 0 0 1 31 23.2" />
        <path d="M32.5 10.2A9 9 0 0 1 32.5 25.8" opacity=".75" />
        <path d="M34 7.6A12 12 0 0 1 34 28.4" opacity=".5" />
      </g>
      <circle cx="28" cy="18" r="1.8" fill="#E14D4D" />
      <text
        x="15"
        y="22.4"
        textAnchor="middle"
        fontFamily="'Bricolage Grotesque', Arial, sans-serif"
        fontWeight="800"
        fontSize="12.5"
        fill={letters}
      >
        TS
      </text>
    </svg>
  );
}

export function Wordmark({ size = 36, onDark = false }: { size?: number; onDark?: boolean }) {
  return (
    <span className={`wordmark${onDark ? " wordmark--dark" : ""}`} style={{ fontSize: size * 0.62 }}>
      <LogoMark size={size} onDark={onDark} />
      <span>
        Tag<span className="wordmark__scout">Scout</span>
      </span>
    </span>
  );
}

/** Decorative signal rings radiating from a point, used in the hero panels. */
export function SignalRings({ className = "" }: { className?: string }) {
  const rings = [60, 110, 160, 210, 260, 310];
  return (
    <svg className={`signal-rings ${className}`} viewBox="0 0 360 360" aria-hidden="true">
      {rings.map((r, i) => {
        const a = (Math.PI / 180) * 62;
        const x1 = 40 + r * Math.cos(-a);
        const y1 = 180 + r * Math.sin(-a);
        const y2 = 180 + r * Math.sin(a);
        return (
          <path
            key={r}
            d={`M${x1.toFixed(1)} ${y1.toFixed(1)}A${r} ${r} 0 0 1 ${x1.toFixed(1)} ${y2.toFixed(1)}`}
            fill="none"
            stroke="#F2A531"
            strokeWidth={i === 0 ? 5 : 4}
            strokeLinecap="round"
            opacity={Math.max(0.12, 0.9 - i * 0.15)}
          />
        );
      })}
      <circle cx="40" cy="180" r="9" fill="#E14D4D" />
      <circle cx="40" cy="180" r="18" fill="none" stroke="#E14D4D" strokeWidth="2" opacity=".45" />
    </svg>
  );
}
