import type { AvatarColor, AvatarShape, Bot } from '@shared/types';
import { palette } from '../lib/colors';
import { Icon } from '../components/Icon';
import { FACE, SHAPES, type ShapeDef } from './shapes.gen';

export function shapeDef(shape: AvatarShape | string): ShapeDef {
  return SHAPES[shape] ?? SHAPES.blob;
}

/** Shapes offered in the Create New Bot picker, in the design's order (rows of 5/5/3). */
export const PICKER_SHAPES: AvatarShape[] = ['blob', 'cloud', 'square', 'sparkle', 'clover', 'heart', 'flower', 'teardrop', 'tablet', 'wedge', 'house', 'star', 'hex'];

interface Props {
  shape: AvatarShape | string;
  color: AvatarColor | string;
  /** Slot size in px. The drawing overflows the slot a little, so the body fills it. */
  size: number;
  star?: boolean;
  /** Selected-shape ring (a gap, then a gray outline following the silhouette). */
  ring?: boolean;
  className?: string;
  title?: string;
}

/** The characters are drawn in a 100 x 100 box with a margin around the body. */
const BOX = 100;
const OVERDRAW = 1.16;
const VISOR_DARK = '#16171A';
const VISOR_LIGHT = '#F2F2F2';

/** True for a body colour dark enough that the visor has to be the light part. */
function isDark(hex: string): boolean {
  const n = parseInt(hex.slice(1, 7), 16);
  return (0.2126 * ((n >> 16) & 255) + 0.7152 * ((n >> 8) & 255) + 0.0722 * (n & 255)) / 255 < 0.3;
}

/** A bot's character: a body in the bot's colour and a visor with two lit eyes (see scripts/gen-avatars.mjs). */
export function BotAvatar({ shape, color, size, star, ring, className, title }: Props) {
  const def = shapeDef(shape);
  const draw = size * OVERDRAW;
  const off = (draw - size) / 2;
  const fill = palette(color).fill;
  const dark = isDark(fill);
  const [x, y, s] = def.face;
  const w = FACE.width * s, h = FACE.height * s;
  // Stroke widths in box units: the box maps to `draw` px.
  const px = BOX / draw;
  return (
    <span className={`bot-avatar ${className ?? ''}`} style={{ width: size, height: size }} title={title} role="img" aria-label={title}>
      <svg width={draw} height={draw} viewBox={`0 0 ${BOX} ${BOX}`} style={{ position: 'absolute', left: -off, top: -off, overflow: 'visible' }} aria-hidden>
        {ring && <path d={def.body} fill="#1C1C1C3D" stroke="#1C1C1C3D" strokeWidth={px * 2 * 3.4} strokeLinejoin="round" />}
        {ring && <path d={def.body} fill="#FFFFFF" stroke="#FFFFFF" strokeWidth={px * 2 * 2.4} strokeLinejoin="round" />}
        <path d={def.body} fill={fill} />
        <rect x={x - w / 2} y={y - h / 2} width={w} height={h} rx={h / 2} fill={dark ? VISOR_LIGHT : VISOR_DARK} />
        {[-1, 1].map((side) => <circle key={side} cx={x + side * FACE.eyeGap * s} cy={y} r={FACE.eyeRadius * s} fill={dark ? VISOR_DARK : '#FFFFFF'} />)}
      </svg>
      {star && (
        <span className="bot-avatar__star" style={{ width: size * 0.2667, height: size * 0.2667 }}>
          <Icon name="star" size={size * 0.1333} color="#FFFFFF" />
        </span>
      )}
    </span>
  );
}

export function BotAvatarFor({ bot, size, star, className }: { bot: Pick<Bot, 'avatarShape' | 'avatarColor' | 'name'> & { isPrimary?: boolean }; size: number; star?: boolean; className?: string }) {
  return <BotAvatar shape={bot.avatarShape} color={bot.avatarColor} size={size} star={star} className={className} title={bot.name} />;
}
