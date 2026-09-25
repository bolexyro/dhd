import type {
  CompanionClientApi,
  CompanionState,
  DiscoveredPhoneSnapshot,
} from "./api.js";

const CHECK_REQUEST_TIMEOUT_MS = 18_000;

export function createWebApi(): CompanionClientApi {
  return {
    async getState(): Promise<CompanionState> {
      const controller = new AbortController();
      const timeoutId = window.setTimeout(() => controller.abort(), 5_000);
      try {
        const res = await fetch("/api/state", { cache: "no-store", signal: controller.signal });
        if (!res.ok) throw new Error(`Server returned ${res.status}: ${res.statusText}`);
        return res.json();
      } finally {
        window.clearTimeout(timeoutId);
      }
    },
    async discoverPhones(): Promise<DiscoveredPhoneSnapshot[]> {
      const res = await fetch("/api/discover", { method: "POST" });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      const data = await res.json() as { phones?: DiscoveredPhoneSnapshot[] };
      return Array.isArray(data.phones) ? data.phones : [];
    },
    async pairWithDiscoveredPhone(input): Promise<CompanionState> {
      const res = await fetch("/api/pair-device", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(input)
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      return res.json();
    },
    async checkConnection() {
      const controller = new AbortController();
      const timeoutId = window.setTimeout(() => controller.abort(), CHECK_REQUEST_TIMEOUT_MS);
      try {
        const res = await fetch("/api/check", { method: "POST", signal: controller.signal });
        if (!res.ok) {
          const data = await res.json().catch(() => ({}));
          throw new Error(data.message || `Server returned ${res.status}`);
        }
        return res.json();
      } catch (error) {
        if (controller.signal.aborted) {
          throw new Error("Timed out checking the phone assistant bridge.");
        }
        throw error;
      } finally {
        window.clearTimeout(timeoutId);
      }
    },
    async clearLogs(): Promise<CompanionState> {
      const res = await fetch("/api/clear-logs", { method: "POST" });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      return res.json();
    },
    async clearToolCalls(): Promise<CompanionState> {
      const res = await fetch("/api/clear-tool-calls", { method: "POST" });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      return res.json();
    },
    onState(callback: (state: CompanionState) => void) {
      const source = new EventSource("/api/events");
      source.onmessage = (event) => {
        try {
          const state = JSON.parse(event.data) as CompanionState;
          callback(state);
        } catch (err) {
          console.error("Failed to parse SSE state:", err);
        }
      };

      // Hot reload listener for live development
      source.addEventListener("reload", (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data) as { type?: string; file?: string };
          if (data.type === "css") {
            const links = document.querySelectorAll<HTMLLinkElement>('link[rel="stylesheet"]');
            links.forEach((link) => {
              const url = new URL(link.href, window.location.origin);
              url.searchParams.set("_reload", String(Date.now()));
              link.href = url.toString();
            });
          } else {
            window.location.reload();
          }
        } catch {
          window.location.reload();
        }
      });

      return () => source.close();
    }
  };
}
