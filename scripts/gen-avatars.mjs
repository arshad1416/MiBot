// MiBot's own bot characters, drawn from arithmetic rather than from anyone's artwork.
//
//   node scripts/gen-avatars.mjs            writes the shape tables the apps draw from
//   node scripts/gen-avatars.mjs --preview  also writes docs/avatars.svg, a sheet of every shape
//
// A character is a body (one of the 25 shape names the hub knows) with a face: a dark visor with two
// lit eyes. Everything is in a 100 x 100 box. The body is an SVG path; the face is where the visor
// sits and how big it is, so each app can draw it and move the eyes itself.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TAU = Math.PI * 2;
const n2 = (v) => String(Math.round(v * 100) / 100);

// ---------- ways to make an outline ----------

/** A closed smooth curve through points (Catmull-Rom, written as cubic Béziers). */
function smooth(points) {
  const n = points.length;
  const at = (i) => points[((i % n) + n) % n];
  let d = `M${n2(points[0][0])} ${n2(points[0][1])}`;
  for (let i = 0; i < n; i++) {
    const [p0, p1, p2, p3] = [at(i - 1), at(i), at(i + 1), at(i + 2)];
    const c1 = [p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6];
    const c2 = [p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6];
    d += `C${n2(c1[0])} ${n2(c1[1])} ${n2(c2[0])} ${n2(c2[1])} ${n2(p2[0])} ${n2(p2[1])}`;
  }
  return `${d}Z`;
}

/** Points of a curve given by angle: `at(t)` returns [x, y] around the origin; the result is centred in the box. */
const curve = (at, steps = 72, cx = 50, cy = 50) => smooth(Array.from({ length: steps }, (_, i) => { const [x, y] = at((i / steps) * TAU); return [cx + x, cy + y]; }));

/** A superellipse: n = 2 is an ellipse, larger n is squarer. */
const superellipse = (a, b, n) => curve((t) => {
  const c = Math.cos(t), s = Math.sin(t);
  return [a * Math.sign(c) * Math.abs(c) ** (2 / n), b * Math.sign(s) * Math.abs(s) ** (2 / n)];
});

/** A polygon with every corner rounded by `r` (a list gives each corner its own). */
function rounded(vertices, r) {
  const n = vertices.length;
  let d = '';
  for (let i = 0; i < n; i++) {
    const [prev, cur, next] = [vertices[(i + n - 1) % n], vertices[i], vertices[(i + 1) % n]];
    const radius = Array.isArray(r) ? r[i] : r;
    const toward = (from, to) => {
      const len = Math.hypot(to[0] - from[0], to[1] - from[1]);
      const k = Math.min(radius, len / 2) / len;
      return [from[0] + (to[0] - from[0]) * k, from[1] + (to[1] - from[1]) * k];
    };
    const a = toward(cur, prev), b = toward(cur, next);
    d += `${i ? 'L' : 'M'}${n2(a[0])} ${n2(a[1])}Q${n2(cur[0])} ${n2(cur[1])} ${n2(b[0])} ${n2(b[1])}`;
  }
  return `${d}Z`;
}

const regular = (sides, radius, turn = -Math.PI / 2) => Array.from({ length: sides }, (_, i) => [50 + radius * Math.cos(turn + (i / sides) * TAU), 50 + radius * Math.sin(turn + (i / sides) * TAU)]);
const circle = (cx, cy, r) => `M${n2(cx - r)} ${n2(cy)}A${n2(r)} ${n2(r)} 0 1 1 ${n2(cx + r)} ${n2(cy)}A${n2(r)} ${n2(r)} 0 1 1 ${n2(cx - r)} ${n2(cy)}Z`;

// ---------- the 25 bodies ----------
// `face`: [centre x, centre y, size]. Size 1 is a visor 46 wide.

const star = (points, outer, inner) => Array.from({ length: points * 2 }, (_, i) => {
  const r = i % 2 ? inner : outer, t = -Math.PI / 2 + (i / (points * 2)) * TAU;
  return [50 + r * Math.cos(t), 50 + r * Math.sin(t)];
});

const SHAPES = {
  blob: { body: superellipse(38, 38, 2), face: [50, 46, 1] },
  pebble: { body: curve((t) => [41 * Math.cos(t) + 3 * Math.cos(2 * t), 31 * Math.sin(t) + 2.5 * Math.sin(2 * t + 0.6)]), face: [51, 47, 1] },
  bean: { body: curve((t) => { const x = 40 * Math.cos(t), y = 29 * Math.sin(t); return [x, y - 6 + 0.0075 * x * x]; }), face: [50, 47, 0.98] },
  egg: { body: curve((t) => [(32 + 5 * Math.sin(t)) * Math.cos(t), 40 * Math.sin(t)]), face: [50, 52, 0.95] },
  squircle: { body: superellipse(37, 37, 4.2), face: [50, 45, 1.05] },
  tablet: { body: rounded([[10, 22], [90, 22], [90, 78], [10, 78]], 17), face: [50, 46, 1.1] },
  capsule: { body: rounded([[9, 29], [91, 29], [91, 71], [9, 71]], 21), face: [50, 49, 1.05] },
  cylinder: { body: curve((t) => [30 * Math.sign(Math.cos(t)) * Math.abs(Math.cos(t)) ** 0.45, 39 * Math.sin(t)]), face: [50, 44, 0.9] },
  hex: { body: rounded(regular(6, 41), 11), face: [50, 47, 1] },
  gem: { body: rounded([[27, 15], [73, 15], [91, 39], [50, 88], [9, 39]], [9, 9, 10, 12, 10]), face: [50, 38, 0.98] },
  crystal: { body: rounded([[50, 7], [80, 30], [80, 70], [50, 93], [20, 70], [20, 30]], [10, 9, 9, 10, 9, 9]), face: [50, 47, 0.88] },
  wedge: { body: rounded([[13, 36], [87, 15], [87, 85], [13, 85]], [15, 13, 15, 15]), face: [53, 55, 1] },
  shield: { body: rounded([[13, 14], [87, 14], [87, 50], [50, 92], [13, 50]], [13, 13, 24, 14, 24]), face: [50, 38, 1.05] },
  dome: { body: rounded([[10, 80], [10, 58], [22, 28], [50, 15], [78, 28], [90, 58], [90, 80]], [13, 22, 26, 34, 26, 22, 13]), face: [50, 52, 1.05] },
  arch: { body: rounded([[19, 90], [19, 40], [29, 16], [50, 8], [71, 16], [81, 40], [81, 90]], [12, 18, 22, 26, 22, 18, 12]), face: [50, 43, 0.92] },
  cloud: { body: [circle(31, 57, 21), circle(50, 41, 24), circle(70, 56, 20), rounded([[14, 52], [86, 52], [86, 78], [14, 78]], 13)].join(''), face: [50, 55, 1.02] },
  teardrop: { body: curve((t) => { const s = Math.sin(t); const w = s < 0 ? 1 - 0.78 * (-s) ** 1.6 : 1; return [31 * Math.cos(t) * w, (s < 0 ? 48 : 31) * s + 9]; }), face: [50, 59, 0.9] },
  leaf: { body: rounded([[84, 14], [86, 54], [58, 86], [16, 86], [14, 46], [42, 14]], [5, 30, 30, 5, 30, 30]), face: [50, 50, 0.95] },
  square: { body: rounded([[14, 14], [86, 14], [86, 86], [14, 86]], 15), face: [50, 44, 1.1] },
  sparkle: { body: rounded(star(4, 45, 21), [7, 20, 7, 20, 7, 20, 7, 20]), face: [50, 50, 0.72] },
  clover: { body: [circle(34, 34, 21), circle(66, 34, 21), circle(34, 66, 21), circle(66, 66, 21), circle(50, 50, 16)].join(''), face: [50, 50, 1.02] },
  flower: { body: [...Array.from({ length: 6 }, (_, i) => circle(50 + 24 * Math.cos((i / 6) * TAU + Math.PI / 6), 50 + 24 * Math.sin((i / 6) * TAU + Math.PI / 6), 17)), circle(50, 50, 24)].join(''), face: [50, 50, 0.92] },
  heart: { body: curve((t) => [2.45 * 16 * Math.sin(t) ** 3, -2.45 * (13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t)) - 5], 96), face: [50, 41, 0.95] },
  house: { body: rounded([[50, 9], [89, 41], [89, 88], [11, 88], [11, 41]], [11, 10, 13, 13, 10]), face: [50, 57, 1.05] },
  star: { body: rounded(star(5, 46, 25), [8, 16, 8, 16, 8, 16, 8, 16, 8, 16]), face: [50, 53, 0.68] },
};

/** The visor around a face centre: a capsule 46 wide and 19 high at size 1, with eyes 11.5 either side of the middle. */
export const FACE = { width: 46, height: 19, eyeGap: 11.5, eyeRadius: 4.4 };

// ---------- output ----------

const names = Object.keys(SHAPES);
const header = 'Made by scripts/gen-avatars.mjs. Do not edit: change the script and run it again.';

function kotlin() {
  const rows = names.map((n) => `    "${n}" to AvatarShape("${SHAPES[n].body}", ${SHAPES[n].face[0]}f, ${SHAPES[n].face[1]}f, ${SHAPES[n].face[2]}f),`).join('\n');
  return `package app.mibot.ui

// ${header}

/** A bot's body as an SVG path in a 100 x 100 box, and where its face sits: centre and size (1 = a visor ${FACE.width} wide). */
class AvatarShape(val body: String, val faceX: Float, val faceY: Float, val faceSize: Float)

object AvatarFace {
    const val WIDTH = ${FACE.width}f
    const val HEIGHT = ${FACE.height}f
    const val EYE_GAP = ${FACE.eyeGap}f
    const val EYE_RADIUS = ${FACE.eyeRadius}f
}

val AVATAR_SHAPES: Map<String, AvatarShape> = mapOf(
${rows}
)
`;
}

function typescript() {
  // FACE and the table are plain JSON, each on one line, so the hub can read them as text without a build step (server/src/desk.ts).
  const table = Object.fromEntries(names.map((n) => [n, { body: SHAPES[n].body, face: SHAPES[n].face }]));
  return `// ${header}

/** A bot's body as an SVG path in a 100 x 100 box, and where its face sits: [centre x, centre y, size] (size 1 = a visor ${FACE.width} wide). */
export interface ShapeDef { body: string; face: [number, number, number] }

export const FACE = ${JSON.stringify(FACE)};

export const SHAPES: Record<string, ShapeDef> = ${JSON.stringify(table)};
`;
}

/** One character as SVG markup, for the preview sheet and the app icons. */
export function character(name, { fill = '#1E86FC', visor = '#16171A', eye = '#FFFFFF' } = {}) {
  const { body, face: [x, y, s] } = SHAPES[name];
  const w = FACE.width * s, h = FACE.height * s;
  return `<path d="${body}" fill="${fill}"/><rect x="${n2(x - w / 2)}" y="${n2(y - h / 2)}" width="${n2(w)}" height="${n2(h)}" rx="${n2(h / 2)}" fill="${visor}"/>`
    + [-1, 1].map((side) => `<circle cx="${n2(x + side * FACE.eyeGap * s)}" cy="${n2(y)}" r="${n2(FACE.eyeRadius * s)}" fill="${eye}"/>`).join('');
}

function preview() {
  const colors = ['#F2F2F2', '#A06B39', '#FB2B3E', '#FD690F', '#F59312', '#14C16F', '#13B7A2', '#1E86FC', '#8F59F9', '#F93498', '#959595'];
  const cells = names.map((n, i) => `<g transform="translate(${(i % 5) * 130 + 15} ${Math.floor(i / 5) * 140 + 15})">${character(n, { fill: colors[i % colors.length] })}<text x="50" y="118" text-anchor="middle" font-family="system-ui, sans-serif" font-size="13" fill="#9A9AA0">${n}</text></g>`).join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 660 715" width="660" height="715"><rect width="660" height="715" fill="#1C1C1E"/>${cells}</svg>\n`;
}

// ---------- the app's own mark: the blob character, light on dark ----------

const MARK = 'blob';
const INK = '#16171A';
const LIGHT = '#F2F2F2';
const BACKDROP = '#1C1C1E';

const capsule = (x, y, w, h) => `M${n2(x - w / 2 + h / 2)} ${n2(y - h / 2)}h${n2(w - h)}a${n2(h / 2)} ${n2(h / 2)} 0 0 1 0 ${n2(h)}h${n2(h - w)}a${n2(h / 2)} ${n2(h / 2)} 0 0 1 0 ${n2(-h)}Z`;
const dot = (x, y, r) => `M${n2(x - r)} ${n2(y)}a${n2(r)} ${n2(r)} 0 1 0 ${n2(2 * r)} 0a${n2(r)} ${n2(r)} 0 1 0 ${n2(-2 * r)} 0Z`;

/** The mark's three layers as paths: body, visor, eyes. */
function markPaths() {
  const { body, face: [x, y, s] } = SHAPES[MARK];
  const eyes = [-1, 1].map((side) => dot(x + side * FACE.eyeGap * s, y, FACE.eyeRadius * s)).join('');
  return { body, visor: capsule(x, y, FACE.width * s, FACE.height * s), eyes };
}

function favicon() {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" rx="22" fill="${BACKDROP}"/><g transform="translate(12 12) scale(0.76)">${character(MARK, { fill: LIGHT, visor: INK })}</g></svg>\n`;
}

/** An Android vector drawable. `inset` and `scale` place the 100-box drawing inside a `size` box. */
function drawable(size, inset, scale, paths) {
  const rows = paths.map(([d, color, evenOdd]) => `        <path android:fillColor="${color}"${evenOdd ? ' android:fillType="evenOdd"' : ''} android:pathData="${d}" />`).join('\n');
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- ${header} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${size}dp" android:height="${size}dp" android:viewportWidth="${size}" android:viewportHeight="${size}">
    <group android:translateX="${inset}" android:translateY="${inset}" android:scaleX="${scale}" android:scaleY="${scale}">
${rows}
    </group>
</vector>
`;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const m = markPaths();
  const res = path.join(repo, 'android/app/src/main/res/drawable');
  fs.mkdirSync(res, { recursive: true });
  fs.writeFileSync(path.join(repo, 'android/app/src/main/kotlin/app/mibot/ui/AvatarShapes.kt'), kotlin());
  // The launcher icon's drawing has to stay inside the middle 66 of its 108 box.
  fs.writeFileSync(path.join(res, 'ic_launcher_foreground.xml'), drawable(108, 26, 0.56, [[m.body, LIGHT], [m.visor, INK], [m.eyes, '#FFFFFF']]));
  // A status bar icon is one colour: the visor is a hole in the body, and the eyes are filled back in.
  fs.writeFileSync(path.join(res, 'ic_stat_bot.xml'), drawable(24, 1.2, 0.216, [[m.body + m.visor + m.eyes, '#FFFFFF', true]]));
  fs.writeFileSync(path.join(repo, 'web/src/avatar/shapes.gen.ts'), typescript());
  fs.writeFileSync(path.join(repo, 'web/public/favicon.svg'), favicon());
  if (process.argv.includes('--preview')) fs.writeFileSync(path.join(repo, 'docs/avatars.svg'), preview());
  console.log(`${names.length} shapes written`);
}
