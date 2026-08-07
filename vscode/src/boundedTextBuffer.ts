const DEFAULT_MAXIMUM_LENGTH = 1024 * 1024;

/** Keeps diagnostics useful without allowing a noisy child process to exhaust the extension host. */
export class BoundedTextBuffer {
  private readonly chunks: string[] = [];
  private length = 0;
  private truncated = false;

  constructor(private readonly maximumLength = DEFAULT_MAXIMUM_LENGTH) {
    if (!Number.isSafeInteger(maximumLength) || maximumLength <= 0) {
      throw new RangeError("maximumLength must be a positive safe integer");
    }
  }

  append(text: string): void {
    if (!text) return;
    if (text.length >= this.maximumLength) {
      const discardedOutput = this.length > 0 || text.length > this.maximumLength;
      this.chunks.length = 0;
      this.chunks.push(text.slice(-this.maximumLength));
      this.length = this.maximumLength;
      this.truncated ||= discardedOutput;
      return;
    }

    this.chunks.push(text);
    this.length += text.length;
    while (this.length > this.maximumLength) {
      const excess = this.length - this.maximumLength;
      const first = this.chunks[0];
      if (first.length <= excess) {
        this.chunks.shift();
        this.length -= first.length;
      } else {
        this.chunks[0] = first.slice(excess);
        this.length -= excess;
      }
      this.truncated = true;
    }
  }

  toString(): string {
    const value = this.chunks.join("");
    return this.truncated ? `[earlier output omitted]\n${value}` : value;
  }
}
