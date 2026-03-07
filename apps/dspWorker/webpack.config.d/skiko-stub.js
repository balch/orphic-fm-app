// DSP Worker does not use Compose/Skiko — provide a Proxy-based stub
// that returns no-op functions for any skiko API property access.
// This satisfies WebAssembly.instantiate() import requirements without
// bundling the real Skiko library.
//
// We write the stub inline via a temporary file so that webpack can
// resolve it regardless of the working directory.
const fs = require("fs");
const path = require("path");
const os = require("os");

const stubCode = `
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
`;

const stubPath = path.join(os.tmpdir(), "orpheus-skiko-noop.js");
fs.writeFileSync(stubPath, stubCode);

config.resolve = config.resolve || {};
config.resolve.alias = config.resolve.alias || {};
config.resolve.alias["./skiko.mjs"] = stubPath;
