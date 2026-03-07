// dsp-worker-entry.js — Web Worker bootstrap
// Stub browser APIs that don't exist in Worker context but are
// referenced by transitive Kotlin/WASM dependencies (Compose runtime,
// kotlinx-browser).
if (typeof document === 'undefined') {
    self.document = { createElement: function() { return { style: {} }; }, head: null, body: null };
}
if (typeof window === 'undefined') {
    self.window = self;
}

// Monkey-patch WebAssembly.instantiateStreaming to intercept the import
// object and replace the "./skiko.mjs" section with no-op function stubs.
// Webpack's ES module interop wraps our Proxy-based skiko stub into a
// plain namespace object, stripping the Proxy traps. By patching at the
// WebAssembly level, we bypass webpack entirely.
var _origInstantiateStreaming = WebAssembly.instantiateStreaming;
WebAssembly.instantiateStreaming = function(source, importObject, options) {
    if (importObject && importObject["./skiko.mjs"]) {
        var noopFn = function() { return 0; };
        importObject["./skiko.mjs"] = new Proxy({}, {
            get: function(_, prop) { return noopFn; }
        });
    }
    return _origInstantiateStreaming.call(WebAssembly, source, importObject, options);
};

// Loads the Kotlin/WASM DSP module (webpack UMD bundle)
importScripts('./dsp-worker.js');
