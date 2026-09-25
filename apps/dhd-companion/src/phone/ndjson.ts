export class NdjsonLineBuffer {
  private buffered = "";

  *readLines(chunk: Buffer): Generator<string> {
    this.buffered += chunk.toString("utf8");
    let newline = this.buffered.indexOf("\n");
    while (newline >= 0) {
      const line = this.buffered.slice(0, newline).trim();
      this.buffered = this.buffered.slice(newline + 1);
      newline = this.buffered.indexOf("\n");
      if (line) yield line;
    }
  }
}
