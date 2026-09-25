export function initTheme(): void {
  // Theme Management
  const themeToggle = document.getElementById("theme-toggle") as HTMLButtonElement | null;
  const sunIcon = document.querySelector(".theme-icon-sun") as SVGElement | null;
  const moonIcon = document.querySelector(".theme-icon-moon") as SVGElement | null;

  function applyTheme(theme: "dark" | "light") {
    document.documentElement.setAttribute("data-theme", theme);
    localStorage.setItem("dhd_companion_theme", theme);
    if (sunIcon && moonIcon) {
      if (theme === "light") {
        sunIcon.style.display = "none";
        moonIcon.style.display = "block";
      } else {
        sunIcon.style.display = "block";
        moonIcon.style.display = "none";
      }
    }
  }

  const savedTheme = (localStorage.getItem("dhd_companion_theme") as "dark" | "light" | null) || "dark";
  applyTheme(savedTheme);

  if (themeToggle) {
    themeToggle.addEventListener("click", () => {
      const currentTheme = document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark";
      const nextTheme = currentTheme === "dark" ? "light" : "dark";
      applyTheme(nextTheme);
    });
  }
}
