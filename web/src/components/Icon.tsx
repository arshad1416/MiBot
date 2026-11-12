import type { CSSProperties } from 'react';
import { ICONS, type IconDef, type IconName } from './icons.gen';

export type { IconName };

interface Props { name: IconName; size?: number; opacity?: number; className?: string; style?: CSSProperties; color?: string }

/** One of MiBot's own icons (see scripts/gen-icons.mjs), in the colour of the text around it unless `color` is given. */
export function Icon({ name, size = 16, opacity, className, style, color }: Props) {
  const icon: IconDef = ICONS[name];
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 24 24" aria-hidden style={{ opacity, color, flexShrink: 0, ...style }}>
      {icon.fill && <path d={icon.fill} fill="currentColor" />}
      {icon.stroke && <path d={icon.stroke} fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" />}
    </svg>
  );
}
