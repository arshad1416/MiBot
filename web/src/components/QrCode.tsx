import { useMemo } from 'react';
import qrcode from 'qrcode-generator';

/** Modules of quiet space around the code, which scanners need. */
const MARGIN = 4;

/** A QR code of `text`. Always dark on white, whatever the theme: that is what cameras read best. */
export function QrCode({ text, size, label }: { text: string; size: number; label: string }) {
  const { side, path } = useMemo(() => {
    const qr = qrcode(0, 'M');
    qr.addData(text);
    qr.make();
    const n = qr.getModuleCount();
    let d = '';
    for (let row = 0; row < n; row++) for (let col = 0; col < n; col++) if (qr.isDark(row, col)) d += `M${col + MARGIN} ${row + MARGIN}h1v1h-1z`;
    return { side: n + 2 * MARGIN, path: d };
  }, [text]);
  return (
    <svg className="qr" width={size} height={size} viewBox={`0 0 ${side} ${side}`} role="img" aria-label={label} shapeRendering="crispEdges">
      <rect width={side} height={side} fill="#FFFFFF" />
      <path d={path} fill="#000000" />
    </svg>
  );
}
