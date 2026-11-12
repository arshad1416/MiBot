/** The page a worker with no settings serves on its own machine (server/src/worker/pairing.ts). */
export const SETUP_PAGE = '127.0.0.1:8765';

/** That page with the hub's address and a code filled in. It still asks before it connects. */
export const setupPageLink = (hubUrl: string, code: string) => `http://${SETUP_PAGE}/#hub=${encodeURIComponent(hubUrl)}&code=${code}`;
