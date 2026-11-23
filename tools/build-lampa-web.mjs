import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { cp, mkdir, readFile, rm, writeFile, copyFile } from "node:fs/promises";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceRoot = path.join(repoRoot, "third_party", "lampa-source");
const require = createRequire(path.join(sourceRoot, "package.json"));
const { rollup } = require("rollup");
const { babel } = require("@rollup/plugin-babel");
const commonjs = require("@rollup/plugin-commonjs");
const { nodeResolve } = require("@rollup/plugin-node-resolve");
const worker = require("rollup-plugin-web-worker-loader");
const sass = require("sass");
const autoprefixer = require("autoprefixer");
const postcss = require("postcss");

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
await rm(path.join(sourceRoot, "dest"), { recursive: true, force: true });
await mkdir(outputRoot, { recursive: true });
await cp(publicRoot, outputRoot, { recursive: true });

const bundle = await rollup({
  input: path.join(sourceRoot, "src", "app.js"),
  plugins: [
    babel({
      babelHelpers: "bundled",
      presets: [require.resolve("@babel/preset-env")]
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

// Keep the upstream Gulp build's browser-platform substitution.
const appBundlePath = path.join(outputRoot, "app.js");
const appBundle = (await readFile(appBundlePath, "utf8"))
  .replace(/return kIsNodeJS/g, "return false");
await writeFile(appBundlePath, appBundle, "utf8");

// Compile every first-party Lampa plugin using the source repository's own
// pack_plugins task. Copy plugin assets and generated scripts into the APK.
execFileSync(
  process.execPath,
  [path.join(sourceRoot, "node_modules", "gulp", "bin", "gulp.js"), "pack_plugins"],
  { cwd: sourceRoot, stdio: "inherit" }
);
const pluginSourceRoot = path.join(sourceRoot, "plugins");
const pluginOutputRoot = path.join(outputRoot, "plugins");
const compiledPluginRoot = path.join(sourceRoot, "dest", "plugins");
await cp(pluginSourceRoot, pluginOutputRoot, { recursive: true, force: true });
await cp(compiledPluginRoot, pluginOutputRoot, { recursive: true, force: true });

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

const appScriptTag = '<script src="app.js"></script>';
if (!html.includes(appScriptTag)) {
  throw new Error("The upstream Lampa index no longer has the expected app.js entry point");
}
const bridgeScript = [
  '<script>window.__LAMPA_LAMPAC_URL__ = "http://127.0.0.1:9118";</script>',
  '<script src="plugins/modification.js"></script>'
].join("\n");
html = html.replace(appScriptTag, appScriptTag + "\n" + bridgeScript);
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
  throw new Error("The built Lampa web assets appear incomplete");
}
console.log("Complete Lampa web assets: app.js=" + appJs.length + ", app.css=" + css.length);
