import type http from "node:http";

export class SseHub {
  private readonly clients = new Set<http.ServerResponse>();

  add(client: http.ServerResponse): void {
    this.clients.add(client);
  }

  remove(client: http.ServerResponse): void {
    this.clients.delete(client);
  }

  broadcast(payload: string): void {
    for (const client of this.clients) {
      try {
        client.write(payload);
      } catch {
        this.clients.delete(client);
      }
    }
  }
}
