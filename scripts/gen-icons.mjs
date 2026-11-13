// MiBot's own interface icons, written once here and drawn by both apps.
//
//   node scripts/gen-icons.mjs   writes web/src/components/icons.gen.ts and the Android app's IconPaths.kt
//
// Every icon sits in a 24 x 24 box. `stroke` parts are drawn as 1.8-wide lines with round ends and
// corners; `fill` parts are filled. An icon can have both.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const n2 = (v) => String(Math.round(v * 100) / 100);

const circle = (cx, cy, r) => `M${n2(cx - r)} ${cy}a${r} ${r} 0 1 0 ${n2(2 * r)} 0a${r} ${r} 0 1 0 ${n2(-2 * r)} 0Z`;
/** A rectangle with round corners, from its top-left corner. */
const box = (x, y, w, h, r) => `M${n2(x + r)} ${y}h${n2(w - 2 * r)}a${r} ${r} 0 0 1 ${r} ${r}v${n2(h - 2 * r)}a${r} ${r} 0 0 1 ${-r} ${r}h${n2(2 * r - w)}a${r} ${r} 0 0 1 ${-r} ${-r}v${n2(2 * r - h)}a${r} ${r} 0 0 1 ${r} ${-r}Z`;
/** A five-pointed star around (cx, cy). */
function star(cx, cy, outer, inner) {
  const pts = [];
  for (let i = 0; i < 10; i++) {
    const a = -Math.PI / 2 + (i * Math.PI) / 5, r = i % 2 ? inner : outer;
    pts.push(`${n2(cx + r * Math.cos(a))} ${n2(cy + r * Math.sin(a))}`);
  }
  return `M${pts.join('L')}Z`;
}
const MIC = 'M12 3.5a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0v-5a3 3 0 0 0-3-3Z M6 11.5a6 6 0 0 0 12 0 M12 17.5V21';
const STAR = star(12, 12.6, 8.6, 3.9);

export const ICONS = {
  // lines
  plus: { stroke: 'M12 5v14 M5 12h14' },
  close: { stroke: 'M6 6l12 12 M18 6L6 18' },
  check: { stroke: 'M5 12.5l4.5 4.5L19 7.5' },
  chev_down: { stroke: 'M6 9.5l6 6 6-6' },
  chev_right: { stroke: 'M9.5 6l6 6-6 6' },
  chev_left: { stroke: 'M14.5 6l-6 6 6 6' },
  arrow_down: { stroke: 'M12 5v14 M6 13l6 6 6-6' },
  arrow_up: { stroke: 'M12 19V5 M6 11l6-6 6 6' },
  search: { stroke: `${circle(11, 11, 7)} M16.2 16.2L20 20` },
  clock: { stroke: `${circle(12, 12, 8.5)} M12 7.5V12l3 2` },
  apps: { stroke: `${box(4, 4, 6, 6, 1.2)} ${box(14, 4, 6, 6, 1.2)} ${box(4, 14, 6, 6, 1.2)} M17 14v6 M14 17h6` },
  copy: { stroke: `${box(8, 8, 12, 12, 1.6)} M16 8V5.6A1.6 1.6 0 0 0 14.4 4H5.6A1.6 1.6 0 0 0 4 5.6v8.8A1.6 1.6 0 0 0 5.6 16H8` },
  edit: { stroke: 'M4 20l.9-4.4L15.6 4.9a2.2 2.2 0 0 1 3.1 0l.4.4a2.2 2.2 0 0 1 0 3.1L8.4 19.1 4 20Z M13.8 6.7l3.5 3.5' },
  file: { stroke: 'M7 3.5h6.5l5 5V19a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19V5A1.5 1.5 0 0 1 7 3.5Z M13.5 3.5v5h5' },
  folder: { stroke: 'M4 7.5A1.5 1.5 0 0 1 5.5 6h4l2 2.5h7A1.5 1.5 0 0 1 20 10v7.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5v-10Z' },
  mic: { stroke: MIC },
  mic_off: { stroke: `${MIC} M4 4l16 16` },
  paperclip: { stroke: 'M19 11.5l-7.2 7.2a4.5 4.5 0 0 1-6.4-6.4l7.6-7.6a3 3 0 0 1 4.3 4.3l-7.5 7.5a1.5 1.5 0 0 1-2.1-2.1l6.8-6.8' },
  record: { stroke: circle(12, 12, 8), fill: circle(12, 12, 3.5) },
  save: { stroke: 'M12 4v10 M7.5 10l4.5 4.5 4.5-4.5 M5 19h14' },
  reply: { stroke: 'M9.5 6L4 11.5 9.5 17 M4.5 11.5H14a6 6 0 0 1 6 6V19' },
  share: { stroke: 'M12 15V4 M7.5 8L12 3.5 16.5 8 M6 12v6.5A1.5 1.5 0 0 0 7.5 20h9a1.5 1.5 0 0 0 1.5-1.5V12' },
  sidebar: { stroke: `${box(4, 4.5, 16, 15, 1.6)} M9.5 4.5v15` },
  verified: { stroke: `${circle(12, 12, 8.5)} M8.2 12.3l2.6 2.6 5-5.2` },
  voice_a: { stroke: 'M6 10v4 M10 7v10 M14 5v14 M18 9v6' },
  voice_b: { stroke: 'M6 9v6 M10 5v14 M14 8v8 M18 10v4' },
  composer_plus: { stroke: `${circle(12, 12, 8.5)} M12 8v8 M8 12h8` },
  monitor: { stroke: `${box(3, 4.5, 18, 12.5, 1.8)} M12 17v3 M8 20.5h8` },
  refresh: { stroke: 'M19.5 12a7.5 7.5 0 1 1-2.2-5.3 M19.5 4.5v4h-4' },
  eye: { stroke: `M2.6 12s3.4-6.4 9.4-6.4 9.4 6.4 9.4 6.4-3.4 6.4-9.4 6.4S2.6 12 2.6 12Z ${circle(12, 12, 2.7)}` },
  pointer: { stroke: 'M5.4 4.6l5.5 14.8 2.3-6.2 6.2-2.3L5.4 4.6Z' },
  expand: { stroke: 'M14.5 4.5h5v5 M19.5 4.5l-5.8 5.8 M9.5 19.5h-5v-5 M4.5 19.5l5.8-5.8' },
  shrink: { stroke: 'M19.5 9.7h-5.2V4.5 M14.3 9.7l5.4-5.4 M4.5 14.3h5.2v5.2 M9.7 14.3l-5.4 5.4' },
  bell: { stroke: 'M6.5 16.5v-5.3a5.5 5.5 0 0 1 11 0v5.3l1.5 1.5H5l1.5-1.5Z M10 20.5a2.2 2.2 0 0 0 4 0' },
  sliders: { stroke: `M4 7h9 M19 7h1 M4 17h1 M11 17h9 ${circle(16, 7, 2.2)} ${circle(8, 17, 2.2)}` },
  trash: { stroke: 'M4.5 7h15 M9.5 7V4.8h5V7 M6.5 7l.8 11.6a1.5 1.5 0 0 0 1.5 1.4h6.4a1.5 1.5 0 0 0 1.5-1.4L17.5 7 M10 11v5 M14 11v5' },
  link: { stroke: 'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1 M14 10a4 4 0 0 0-5.7 0l-3 3A4 4 0 0 0 11 18.7l1-1' },
  qr: { stroke: `${box(4, 4, 6.5, 6.5, 1.2)} ${box(13.5, 4, 6.5, 6.5, 1.2)} ${box(4, 13.5, 6.5, 6.5, 1.2)} M14 14h2.5v2.5 M20 14v6h-3 M14 20v-.5` },
  user: { stroke: `${circle(12, 8.5, 3.8)} M4.5 20a7.5 7.5 0 0 1 15 0` },
  globe: { stroke: `${circle(12, 12, 8.5)} M3.5 12h17 M12 3.5c2.6 2.4 3.9 5.2 3.9 8.5s-1.3 6.1-3.9 8.5c-2.6-2.4-3.9-5.2-3.9-8.5s1.3-6.1 3.9-8.5Z` },
  phone: { stroke: `${box(7, 3, 10, 18, 2)} M11 17.5h2` },
  // filled
  star: { fill: STAR, stroke: STAR },
  chat_filled: { fill: 'M5.5 4.5h13A2.5 2.5 0 0 1 21 7v8a2.5 2.5 0 0 1-2.5 2.5H12l-4.6 3.3a.6 .6 0 0 1-.9-.5V17.5h-1A2.5 2.5 0 0 1 3 15V7a2.5 2.5 0 0 1 2.5-2.5Z' },
  stop: { fill: box(6, 6, 12, 12, 2.2) },
  play: { fill: 'M8 5.6v12.8a.8 .8 0 0 0 1.2 .7l10.4-6.4a.8 .8 0 0 0 0-1.4L9.2 4.9a.8 .8 0 0 0-1.2 .7Z' },
  more: { fill: `${circle(5, 12, 1.75)} ${circle(12, 12, 1.75)} ${circle(19, 12, 1.75)}` },
};

const header = 'Made by scripts/gen-icons.mjs. Do not edit: change the script and run it again.';
const names = Object.keys(ICONS);
const camel = (s) => s.replace(/_(\w)/g, (_, c) => c.toUpperCase());

function typescript() {
  const rows = names.map((n) => `  ${n}: ${JSON.stringify(ICONS[n])},`).join('\n');
  return `// ${header}

/** One icon in a 24 x 24 box: \`stroke\` is drawn as a 1.8-wide round line, \`fill\` is filled. */
export interface IconDef { stroke?: string; fill?: string }

export const ICONS = {
${rows}
} satisfies Record<string, IconDef>;

export type IconName = keyof typeof ICONS;
`;
}

function kotlin() {
  const q = (s) => (s ? `"${s}"` : 'null');
  const rows = names.map((n) => `    val ${camel(n)} = IconDef(${q(ICONS[n].stroke)}, ${q(ICONS[n].fill)})`).join('\n');
  return `package app.mibot.ui

// ${header}

/** One icon in a 24 x 24 box: \`stroke\` is drawn as a 1.8-wide round line, \`fill\` is filled. */
class IconDef(val stroke: String?, val fill: String?)

object MiIcons {
${rows}
}
`;
}

fs.writeFileSync(path.join(repo, 'web/src/components/icons.gen.ts'), typescript());
fs.writeFileSync(path.join(repo, 'android/app/src/main/kotlin/app/mibot/ui/IconPaths.kt'), kotlin());
console.log(`${names.length} icons written`);
