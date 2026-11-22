import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { cp, mkdir, readFile, rm, writeFile, copyFile } from "node:fs/promises";

const require = createRequire(import.meta.url);
const { rollup } = require("rollup");
const { babel } = require("@rollup/plugin-babel");
const commonjs = require("@rollup/plugin-commonjs");
const { nodeResolve } = require("@rollup/plugin-node-resolve");
const worker = require("rollup-plugin-web-worker-loader");
const sass = require("sass");
const autoprefixer = require("autoprefixer");
const postcss = require("postcss");

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceRoot = path.join(repoRoot, "third_party", "lampa-source");
const outputRoot = path.join(repoRoot, "app", "src", "main", "assets", "lampa");
const publicRoot = path.join(sourceRoot, "public");

for (const required of [
  path.join(sourceRoot, "LICENSE"),
  path.join(sourceRoot, "src", "app.js"),
  path.join(sourceRoot, "src", "sass", "app.scss"),
  path.join(publicRoot, "index.html")
]) {
  await readFile(required);
}

await rm(outputRoot, { recursive: true, force: true });
await mkdir(outputRoot, { recursive: true });
await cp(publicRoot, outputRoot, { recursive: true });

const bundle = await rollup({
  input: path.join(sourceRoot, "src", "app.js"),
  plugins: [
    babel({
      babelHelpers: "bundled",
      presets: ["@babel/preset-env"]
    }),
    commonjs(),
    nodeResolve(),
    worker()
  ],
  onwarn(warning, warn) {
    if (warning.code === "CIRCULAR_DEPENDENCY") return;
    warn(warning);
  }
});

try {
  await bundle.write({
    file: path.join(outputRoot, "app.js"),
    format: "iife",
    sourcemap: false
  });
} finally {
  await bundle.close();
}

const compiledCss = sass.compile(path.join(sourceRoot, "src", "sass", "app.scss"), {
  style: "expanded",
  loadPaths: [path.join(sourceRoot, "src", "sass")]
});
const prefixedCss = await postcss([
  autoprefixer(["last 100 versions", "> 1%", "ie 8", "ie 7", "ios 6", "android 4"])
]).process(compiledCss.css, {
  from: path.join(sourceRoot, "src", "sass", "app.scss"),
  to: path.join(outputRoot, "css", "app.css"),
  map: false
});
await mkdir(path.join(outputRoot, "css"), { recursive: true });
await writeFile(path.join(outputRoot, "css", "app.css"), prefixedCss.css, "utf8");

const indexPath = path.join(outputRoot, "index.html");
let html = await readFile(indexPath, "utf8");
html = html.replace("http://www.youtube.com/iframe_api", "https://www.youtube.com/iframe_api");
const configScript = `<script>
window.lampa_settings = window.lampa_settings || {};
window.lampa_settings.socket_use = false;
window.lampa_settings.account_use = false;
window.lampa_settings.account_sync = false;
window.lampa_settings.plugins_use = false;
window.lampa_settings.plugins_store = false;
window.lampa_settings.torrents_use = true;
window.lampa_settings.disable_features = Object.assign(
    {},
    window.lampa_settings.disable_features || {},
    { ads: true, install_proxy: true, remote_configuration: true }
);
window.__JACKET_LAMPAC_URL__ = "http://127.0.0.1:9118";
</script>`;
const appScriptTag = '<script src="app.js"></script>';
if (!html.includes(appScriptTag)) {
  throw new Error("Lampa public/index.html no longer contains the expected app.js entry point");
}
html = html.replace(appScriptTag, configScript + "\n" + appScriptTag);
await writeFile(indexPath, html, "utf8");

const modificationSource = path.join(repoRoot, "lampa-android", "modification.js");
const modificationTarget = path.join(outputRoot, "plugins", "modification.js");
await mkdir(path.dirname(modificationTarget), { recursive: true });
await copyFile(modificationSource, modificationTarget);
await copyFile(path.join(sourceRoot, "LICENSE"), path.join(outputRoot, "LICENSE"));
await writeFile(
  path.join(outputRoot, "build-info.json"),
  JSON.stringify({
    upstream: "yumata/lampa-source",
    commit: "e583e42238587b2b85afa9f386e9b41adfc16f35",
    appVersion: "3.4.7",
    generatedAt: new Date().toISOString()
  }, null, 2),
  "utf8"
);

const appJs = await readFile(path.join(outputRoot, "app.js"));
const css = await readFile(path.join(outputRoot, "css", "app.css"));
if (appJs.length < 100_000 || css.length < 20_000) {
  throw new Error(`Lampa build output looks incomplete (app.js=${appJs.length}, app.css=${css.length})`);
}
console.log(`Lampa Android assets ready: app.js=${appJs.length}, app.css=${css.length}`);
