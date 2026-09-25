import { errorMessage, toError } from "../shared/errors.js";

const REQUEST_TIMEOUT_MS = 30_000;

export type JsonRpcId = number | string;

export interface JsonRpcMessage {
  id?: JsonRpcId;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: { code?: number; message?: string; data?: unknown };
}

export interface JsonRpcPeer {
  onRequest(message: JsonRpcMessage): void;
  onNotification(message: JsonRpcMessage): void;
}

interface PendingRpcRequest {
  resolve: (message: JsonRpcMessage) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

export class JsonRpcConnection {
  private nextId = 1;
  private readonly pending = new Map<JsonRpcId, PendingRpcRequest>();

  constructor(
    private readonly writeLine: (line: string) => void,
    private readonly peer: JsonRpcPeer,
  ) {}

  handleLine(line: string): void {
    const trimmed = line.trim();
    if (!trimmed) return;
    let message: JsonRpcMessage;
    try {
      message = JSON.parse(trimmed) as JsonRpcMessage;
    } catch {
      console.error(
        `[codex-app-server] ignored non-JSON stdout: ${trimmed.slice(0, 240)}`,
      );
      return;
    }

    // App Server is bidirectional: a message with both `method` and `id` is a
    // server request that this client must answer, not a response to one of
    // our requests. Handling it before the pending map prevents server and
    // client request IDs from colliding.
    if (message.method && message.id !== undefined) {
      this.peer.onRequest(message);
      return;
    }

    if (message.id !== undefined) {
      const waiter = this.pending.get(message.id);
      if (waiter) {
        this.pending.delete(message.id);
        clearTimeout(waiter.timer);
        if (message.error) {
          waiter.reject(
            new Error(
              message.error.message ||
                `Codex App Server request ${message.id} failed.`,
            ),
          );
        } else {
          waiter.resolve(message);
        }
      }
      return;
    }

    this.peer.onNotification(message);
  }

  request(
    method: string,
    params: Record<string, unknown>,
  ): Promise<JsonRpcMessage> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (!this.pending.delete(id)) return;
        reject(
          new Error(
            `Timed out waiting for Codex App Server request ${method}.`,
          ),
        );
      }, REQUEST_TIMEOUT_MS);
      this.pending.set(id, { resolve, reject, timer });
      try {
        this.send({ method, id, params });
      } catch (error) {
        this.pending.delete(id);
        clearTimeout(timer);
        reject(toError(error));
      }
    });
  }

  notify(method: string, params: Record<string, unknown>): void {
    this.send({ method, params });
  }

  respond(id: JsonRpcId, result: unknown): void {
    this.send({ id, result });
  }

  respondError(id: JsonRpcId, code: number, message: string): void {
    try {
      this.send({ id, error: { code, message } });
    } catch (error) {
      // The App Server can interrupt and close its stdin while an async
      // server request handler is still unwinding. A best-effort JSON-RPC
      // error must not become an unhandled rejection that kills the phone
      // companion worker during an otherwise expected shutdown.
      console.error(
        `[codex-app-server] could not send server-request error: ${errorMessage(error)}`,
      );
    }
  }

  rejectPending(error: Error): void {
    for (const waiter of this.pending.values()) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
    this.pending.clear();
  }

  private send(message: JsonRpcMessage): void {
    this.writeLine(`${JSON.stringify(message)}\n`);
  }
}
