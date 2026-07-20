const fs = require("node:fs");
const path = require("node:path");

const source = path.resolve(__dirname, "../../schema/manifest/dps-manifest.schema.json");
const destination = path.resolve(__dirname, "../schemas/dps-manifest.schema.json");
fs.mkdirSync(path.dirname(destination), { recursive: true });
fs.copyFileSync(source, destination);
