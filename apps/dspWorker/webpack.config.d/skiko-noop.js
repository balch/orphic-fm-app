// No-op Skiko stub for the DSP Worker.
// Returns a Proxy that provides no-op functions for any property access,
// satisfying WASM import table requirements without the real Skiko library.
const noopFn = function() { return 0; };
const handler = {
    get: function(_target, prop) {
        if (prop === '__esModule') return true;
        if (prop === 'default') return new Proxy({}, handler);
        return noopFn;
    }
};
const stub = new Proxy({}, handler);
module.exports = stub;
module.exports.skikoApi = stub;
module.exports.default = stub;
