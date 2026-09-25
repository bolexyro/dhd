import type { CodexAppServerClient } from "../codex/app-server-client.js";

export type CodexTurnClient = Pick<
  CodexAppServerClient,
  "runTurn" | "steer" | "interrupt" | "isTurnInFlight" | "canSteer"
>;

export interface ActiveCodexTurn {
  sessionId: string;
  client: CodexTurnClient;
}

/**
 * The phone bridge is pull-based: the desktop companion polls the phone over
 * the adb-forwarded socket. Keep the active App Server client here so those
 * polls can deliver steering input to the same in-flight turn.
 */
let activeCodexTurn: ActiveCodexTurn | null = null;

export function currentCodexTurn(): ActiveCodexTurn | null {
  return activeCodexTurn;
}

export function beginCodexTurn(turn: ActiveCodexTurn): void {
  activeCodexTurn = turn;
}

export function endCodexTurn(sessionId: string): void {
  if (activeCodexTurn?.sessionId === sessionId) activeCodexTurn = null;
}
