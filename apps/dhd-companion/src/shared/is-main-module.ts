export function isMainModule(entryName: string): boolean {
  const script = process.argv[1];
  return script !== undefined && (script.endsWith(`${entryName}.ts`) || script.endsWith(`${entryName}.js`));
}
