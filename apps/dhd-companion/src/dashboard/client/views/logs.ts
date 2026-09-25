import type { CompanionLogEntry } from "../api.js";
import { elements } from "../dom.js";
import { formatTime } from "../format.js";

function renderLogLine(entry: CompanionLogEntry): HTMLElement {
  const row = document.createElement("div");
  row.className = `log-line ${entry.level}`;

  const time = document.createElement("span");
  time.className = "log-time";
  time.textContent = formatTime(entry.timestamp);

  const source = document.createElement("span");
  source.className = `log-source ${entry.source}`;
  source.textContent = entry.source;

  const message = document.createElement("span");
  message.className = "log-msg";
  message.textContent = entry.message;

  row.append(time, source, message);
  return row;
}

export function renderLogs(entries: CompanionLogEntry[]): void {
  elements.logList.replaceChildren();
  const countStr = `${entries.length}`;
  elements.logCount.textContent = `${entries.length} events`;
  elements.logCountBadge.textContent = countStr;

  if (entries.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-console font-mono";
    empty.textContent = "No events recorded. Phone activity and link checks will appear here.";
    elements.logList.append(empty);
    return;
  }

  for (const entry of entries) {
    elements.logList.append(renderLogLine(entry));
  }

  if (elements.logScrollContainer) {
    elements.logScrollContainer.scrollTop = elements.logScrollContainer.scrollHeight;
  }
}
