import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    include: ["tests/**/*.test.ts"],
    setupFiles: ["tests/support/setup.ts"],
    testTimeout: 10_000,
    restoreMocks: true
  }
});
