import { readFileSync } from "node:fs";
import { isAbsolute, relative, resolve } from "node:path";

import { describe, expect, it } from "vitest";

const packageRoot = resolve(import.meta.dirname, "..");

describe("companion build config", () => {
  it("keeps the incremental build info inside the output directory", () => {
    const { compilerOptions } = JSON.parse(readFileSync(resolve(packageRoot, "tsconfig.json"), "utf8")) as {
      compilerOptions: { outDir: string; tsBuildInfoFile?: string };
    };

    expect(compilerOptions.tsBuildInfoFile).toBeDefined();
    const fromOutDir = relative(
      resolve(packageRoot, compilerOptions.outDir),
      resolve(packageRoot, compilerOptions.tsBuildInfoFile ?? ""),
    );
    expect(fromOutDir.startsWith("..") || isAbsolute(fromOutDir)).toBe(false);
  });
});
