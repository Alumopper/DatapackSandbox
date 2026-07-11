const fs = require("node:fs");
const path = require("node:path");

const source = path.resolve(__dirname, "../../cli/build/libs/datapack-sandbox-cli.jar");
const destination = path.resolve(__dirname, "../bin/datapack-sandbox-cli.jar");

if (!fs.existsSync(source)) {
  throw new Error(`CLI jar is missing: ${source}. Run the Gradle :cli:fatJar task before packaging.`);
}

fs.mkdirSync(path.dirname(destination), { recursive: true });
fs.copyFileSync(source, destination);
console.log(`Bundled CLI jar: ${destination}`);
