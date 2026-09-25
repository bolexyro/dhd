import { errorMessage } from "../../shared/errors.js";
import { createWebApi } from "./api-client.js";
import { elements } from "./dom.js";
import { StateSync } from "./state-sync.js";
import { initTabs } from "./views/tabs.js";
import { initTheme } from "./views/theme.js";
import { initToast, showToast } from "./views/toast.js";
import { initTokenUsagePopover } from "./views/token-usage.js";
import { initToolCalls } from "./views/tool-calls.js";

const api = createWebApi();
const stateSync = new StateSync(api);
const { discovery } = stateSync;

initToolCalls();
initToast();
initTokenUsagePopover();
initTabs();

// Clear Logs
if (elements.clearLogs) {
  elements.clearLogs.addEventListener("click", async () => {
    try {
      const nextState = await api.clearLogs();
      stateSync.render(nextState);
    } catch (err) {
      showToast(errorMessage(err), "error");
    }
  });
}

if (elements.clearToolCalls) {
  elements.clearToolCalls.addEventListener("click", async () => {
    try {
      const nextState = await api.clearToolCalls();
      stateSync.render(nextState);
    } catch (err) {
      showToast(errorMessage(err), "error");
    }
  });
}

initTheme();

elements.discoverPhones.addEventListener("click", async () => {
  try {
    await discovery.discoverPhonesOnNetwork();
  } catch (error) {
    showToast(errorMessage(error), "error");
  }
});

api.onState(stateSync.render);
discovery.renderDiscoveredPhones();
void stateSync.refreshState({ verifyConnection: true }).catch((error: unknown) => {
  showToast(errorMessage(error), "error");
}).finally(() => {
  void discovery.discoverPhonesOnNetwork().catch((error: unknown) => {
    showToast(errorMessage(error), "error");
  });
});

// EventSource reconnects on its own, but an interrupted stream can leave a
// still-open page behind the phone. Reconcile from the server while visible.
window.setInterval(() => {
  if (!document.hidden) void stateSync.syncState().catch(() => {});
}, 5_000);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) void stateSync.syncState().catch(() => {});
});
window.addEventListener("focus", () => {
  void stateSync.syncState().catch(() => {});
});
