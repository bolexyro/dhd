import { StringDecoder } from "node:string_decoder";

export class NdjsonLineBuffer {
  private readonly decoder = new StringDecoder("utf8");
  private buffered = "";

  *readLines(chunk: Buffer): Generator<string> {
    this.buffered += this.decoder.write(chunk);
    let newline = this.buffered.indexOf("\n");
    while (newline >= 0) {
      const line = this.buffered.slice(0, newline).trim();
      this.buffered = this.buffered.slice(newline + 1);
      newline = this.buffered.indexOf("\n");
      if (line) yield line;
    }
  }
}
