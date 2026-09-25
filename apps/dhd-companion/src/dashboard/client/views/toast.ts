import { elements } from "../dom.js";

export type ToastVariant = "success" | "error" | "info";

const TOAST_ICONS = {
  success: `<svg class="toast-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path stroke-linecap="round" stroke-linejoin="round" d="M20 6L9 17l-5-5"/></svg>`,
  error: `<svg class="toast-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>`,
  info: `<svg class="toast-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>`
};

let toastTimer: number | undefined;

export function hideToast(): void {
  elements.toast.classList.remove("visible");
  if (toastTimer !== undefined) {
    window.clearTimeout(toastTimer);
    toastTimer = undefined;
  }
}

export function showToast(message: string, variant: ToastVariant = "info"): void {
  if (elements.toastMessage) {
    elements.toastMessage.textContent = message;
  } else {
    elements.toast.textContent = message;
  }

  if (elements.toastIcon) {
    elements.toastIcon.innerHTML = TOAST_ICONS[variant];
  }

  elements.toast.className = `toast-box visible ${variant}`;

  if (toastTimer !== undefined) window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(hideToast, 4_000);
}

export function initToast(): void {
  if (elements.toastClose) {
    elements.toastClose.addEventListener("click", hideToast);
  }
}
