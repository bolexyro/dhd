export class SingleFlight<T> {
  private current: Promise<T> | undefined;

  get inFlight(): Promise<T> | undefined {
    return this.current;
  }

  run(operation: () => Promise<T>): Promise<T> {
    if (this.current) return this.current;
    const promise = operation();
    this.current = promise;
    const clear = () => {
      if (this.current === promise) this.current = undefined;
    };
    promise.then(clear, clear);
    return promise;
  }
}
