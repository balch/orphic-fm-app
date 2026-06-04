/**
 * Thin AudioWorkletProcessor that receives pre-rendered audio buffers
 * from the main thread (WASM DSP) and plays them out.
 *
 * Buffers arrive via MessagePort.postMessage() with Transferable Float32Arrays
 * for zero-copy transfer. Queue depth is reported back to the main thread
 * so it can adapt its render-ahead rate.
 */
class DspOutputProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this._queue = []; // Queue of {left, right} buffer pairs
        this._reportCounter = 0;
        this._underrunCount = 0;
        this.port.onmessage = (e) => {
            if (e.data.type === 'buffer') {
                // Cap queue to prevent unbounded growth if producer over-produces
                if (this._queue.length < 48) {
                    this._queue.push({ left: e.data.left, right: e.data.right });
                }
            }
        };
    }

    process(inputs, outputs, parameters) {
        const output = outputs[0];
        if (!output || output.length < 2) return true;

        const outLeft = output[0];
        const outRight = output[1];

        if (this._queue.length > 0) {
            const buf = this._queue.shift();
            outLeft.set(buf.left.subarray(0, outLeft.length));
            outRight.set(buf.right.subarray(0, outRight.length));
        } else {
            // Underrun — output silence
            outLeft.fill(0);
            outRight.fill(0);
            this._underrunCount++;
        }

        // Report queue depth every 4 process() calls (~11ms) for
        // tighter feedback to the producer (main thread or Worker)
        if (++this._reportCounter >= 4) {
            this._reportCounter = 0;
            this.port.postMessage({ type: 'queueDepth', depth: this._queue.length, underruns: this._underrunCount });
        }

        return true;
    }
}

registerProcessor('dsp-output-processor', DspOutputProcessor);
