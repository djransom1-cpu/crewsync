config.resolve = config.resolve || {};
config.resolve.fallback = {
    ...config.resolve.fallback,
    "os": false,
    "path": false,
    "fs": false,
    "crypto": false
};
