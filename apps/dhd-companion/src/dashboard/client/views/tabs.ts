export function initTabs(): void {
  // Tab Switching
  document.querySelectorAll<HTMLButtonElement>(".tab-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      const tabName = btn.getAttribute("data-tab");
      if (!tabName) return;

      document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
      document.querySelectorAll(".tab-panel").forEach((p) => p.classList.remove("active"));

      btn.classList.add("active");
      const panel = document.getElementById(`tab-${tabName}`);
      if (panel) panel.classList.add("active");
    });
  });
}
