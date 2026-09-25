import type { ChildProcessWithoutNullStreams, SpawnOptionsWithoutStdio } from "node:child_process";
import { EventEmitter } from "node:events";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { PassThrough } from "node:stream";

import { vi } from "vitest";

export type JsonRpcId = number | string;

export interface JsonRpcLine {
  id?: JsonRpcId;
  method?: string;
  params?: Record<string, unknown>;
  result?: unknown;
  error?: { code?: number; message?: string };
}

export interface SpawnRecord {
  command: string;
  args: readonly string[];
  options: SpawnOptionsWithoutStdio;
}

export class JsonRpcFailure {
  constructor(readonly error: { code?: number; message?: string }) {}
}

type RequestHandler = (params: Record<string, unknown>) => unknown;

interface LineWaiter {
  matches: (line: JsonRpcLine) => boolean;
  resolve: (line: JsonRpcLine) => void;
}

class FakeChildProcess extends EventEmitter {
  readonly stdin = new PassThrough();
  readonly stdout = new PassThrough();
  readonly stderr = new PassThrough();
  killed = false;
  exited = false;
  private hung = false;
  private heldExit: [number | null, NodeJS.Signals | null] | null = null;

  constructor(readonly pid: number) {
    super();
  }

  kill(signal: NodeJS.Signals = "SIGTERM"): boolean {
    this.killed = true;
    this.exit(null, signal);
    return true;
  }

  exit(code: number | null, signal: NodeJS.Signals | null): void {
    if (this.exited) return;
    if (this.hung) {
      this.heldExit ??= [code, signal];
      return;
    }
    this.exited = true;
    setImmediate(() => this.emit("close", code, signal));
  }

  hang(): void {
    this.hung = true;
  }

  release(): void {
    this.hung = false;
    const [code, signal] = this.heldExit ?? [0, null];
    this.heldExit = null;
    this.exit(code, signal);
  }
}

const defaultHandlers: Record<string, RequestHandler> = {
  initialize: () => ({}),
  "thread/start": () => ({ thread: { id: "thread-1" } }),
  "thread/resume": (params) => ({ thread: { id: params.threadId } }),
  "thread/name/set": () => ({}),
  "thread/unsubscribe": () => ({}),
  "turn/start": () => ({ turn: { id: "turn-1" } }),
  "turn/interrupt": () => ({}),
  "turn/steer": (params) => ({ turnId: params.expectedTurnId }),
};

/**
 * A scripted `codex app-server` child process. It speaks newline-delimited
 * JSON-RPC over fake stdio pipes so tests exercise the real client transport.
 */
export class FakeAppServer {
  readonly spawns: SpawnRecord[] = [];
  readonly received: JsonRpcLine[] = [];
  readonly codexHome = mkdtempSync(join(tmpdir(), "dhd-codex-home-"));
  readonly runtimeCwd = mkdtempSync(join(tmpdir(), "dhd-codex-runtime-"));
  private readonly handlers = new Map<string, RequestHandler>(Object.entries(defaultHandlers));
  private readonly waiters: LineWaiter[] = [];
  private child: FakeChildProcess | null = null;
  private nextPid = 1000;
  private readonly consumedRequests = new Map<string, number>();

  readonly spawn = (
    command: string,
    args: readonly string[],
    options: SpawnOptionsWithoutStdio,
  ): ChildProcessWithoutNullStreams => {
    this.spawns.push({ command, args, options });
    const child = new FakeChildProcess(this.nextPid++);
    this.child = child;
    let buffered = "";
    child.stdin.setEncoding("utf8");
    child.stdin.on("data", (chunk: string) => {
      buffered += chunk;
      let newline = buffered.indexOf("\n");
      while (newline >= 0) {
        const line = buffered.slice(0, newline);
        buffered = buffered.slice(newline + 1);
        this.receive(child, JSON.parse(line) as JsonRpcLine);
        newline = buffered.indexOf("\n");
      }
    });
    child.stdin.on("finish", () => child.exit(0, null));
    return child as unknown as ChildProcessWithoutNullStreams;
  };

  handle(method: string, handler: RequestHandler): void {
    this.handlers.set(method, handler);
  }

  requests(method?: string): JsonRpcLine[] {
    return this.received.filter(
      (line) => line.id !== undefined && line.method !== undefined && (!method || line.method === method),
    );
  }

  methods(): string[] {
    return this.requests().map((line) => line.method as string);
  }

  nextRequest(method: string): Promise<JsonRpcLine> {
    const index = this.consumedRequests.get(method) ?? 0;
    this.consumedRequests.set(method, index + 1);
    let seen = 0;
    return this.waitFor((line) => line.method === method && line.id !== undefined && seen++ === index);
  }

  responseTo(id: JsonRpcId): Promise<JsonRpcLine> {
    return this.waitFor((line) => line.id === id && line.method === undefined);
  }

  notify(method: string, params: Record<string, unknown> = {}): void {
    this.writeLine({ method, params });
  }

  sendRequest(id: JsonRpcId, method: string, params: Record<string, unknown> = {}): Promise<JsonRpcLine> {
    const response = this.responseTo(id);
    this.writeLine({ id, method, params });
    return response;
  }

  writeRaw(text: string): void {
    this.activeChild().stdout.write(text);
  }

  writeLine(message: JsonRpcLine): void {
    this.writeRaw(`${JSON.stringify(message)}\n`);
  }

  writeStderr(text: string): void {
    this.activeChild().stderr.write(text);
  }

  exit(code: number | null, signal: NodeJS.Signals | null = null): void {
    this.activeChild().exit(code, signal);
  }

  get running(): boolean {
    return this.child !== null && !this.child.exited;
  }

  /** Keep the current child alive through stdin close and kill until released. */
  hangOnShutdown(): () => void {
    const child = this.activeChild();
    child.hang();
    return () => child.release();
  }

  private activeChild(): FakeChildProcess {
    if (!this.child) throw new Error("The fake App Server has not been spawned.");
    return this.child;
  }

  private receive(child: FakeChildProcess, line: JsonRpcLine): void {
    this.received.push(line);
    for (const waiter of [...this.waiters]) {
      if (!waiter.matches(line)) continue;
      this.waiters.splice(this.waiters.indexOf(waiter), 1);
      waiter.resolve(line);
    }
    if (line.id === undefined || !line.method) return;
    const handler = this.handlers.get(line.method);
    if (!handler) return;
    const result = handler(line.params ?? {});
    if (result === undefined || child.exited) return;
    const response = result instanceof JsonRpcFailure
      ? { id: line.id, error: result.error }
      : { id: line.id, result };
    child.stdout.write(`${JSON.stringify(response)}\n`);
  }

  private waitFor(matches: (line: JsonRpcLine) => boolean): Promise<JsonRpcLine> {
    const existing = this.received.find(matches);
    if (existing) return Promise.resolve(existing);
    return new Promise((resolve) => this.waiters.push({ matches, resolve }));
  }
}

/** Point the client at throwaway Codex home and runtime directories. */
export function startFakeAppServer(): FakeAppServer {
  const server = new FakeAppServer();
  vi.stubEnv("PHONE_ASSISTANT_CODEX_HOME", server.codexHome);
  vi.stubEnv("PHONE_ASSISTANT_CODEX_CWD", server.runtimeCwd);
  return server;
}
