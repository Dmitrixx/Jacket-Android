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

// This wrapper-specific transform is build-time only; the vendored upstream source stays untouched.
const torrentSourcePath = path.join(sourceRoot, "src", "interaction", "torrent.js").replace(/\\/g, "/");
const playerSourcePath = path.join(sourceRoot, "src", "interaction", "player.js").replace(/\\/g, "/");
let torrentExternalHandoffPatched = false;
const embeddedAndroidShellCompatibility = {
  name: "lampa-android-shell-compatibility",
  transform(code, id) {
    const sourceId = id.split("?")[0].replace(/\\/g, "/");

    if (sourceId === playerSourcePath) {
      // Torrent starts have an earlier "inner player" branch in upstream Lampa.
      // Intercept torrent media before that branch so the external-player chooser
      // receives the actual TorrServer/GStreamer playback URL, not the magnet URI.
      const upstreamTorrentStart = "    if(launch_player == 'lampa' || launch_player == 'inner' || Video.verifyTube(data.url)) launchInner()";
      const externalTorrentStart = `    /* LAMPA_ANDROID_TORRENT_EXTERNAL */
    if (
        data.torrent_hash &&
        navigator.userAgent.toLowerCase().indexOf('lampa-android') >= 0 &&
        typeof AndroidJS !== 'undefined' &&
        typeof AndroidJS.openExternalPlayer === 'function' &&
        !Video.verifyTube(data.url)
    ) {
        const torrentData = Object.assign({}, data);
        torrentData.url = Torserver.toPlayUrl(data.url);
        if (Array.isArray(torrentData.playlist)) {
            torrentData.playlist = torrentData.playlist
                .filter(item => item && typeof item.url === 'string')
                .map(item => Object.assign({}, item, { url: Torserver.toPlayUrl(item.url) }));
        }

        if (AndroidJS.openExternalPlayer(torrentData.url, JSON.stringify(torrentData))) {
            data.url = torrentData.url;
            data.playlist = torrentData.playlist;
            listener.send('external', torrentData);
        }
        else {
            launchInner();
        }
    }
    else if(launch_player == 'lampa' || launch_player == 'inner' || Video.verifyTube(data.url)) launchInner()`;
      const torrentOccurrences = code.split(upstreamTorrentStart).length - 1;
      if (torrentOccurrences !== 1) {
        throw new Error(
          "The upstream Lampa torrent player branch changed; review the external torrent handoff"
        );
      }
      code = code.replace(upstreamTorrentStart, externalTorrentStart);
      // Verify the handoff against the transformed upstream source before Rollup
      // renames imported module bindings in the generated bundle.
      torrentExternalHandoffPatched =
        code.includes("Torserver.toPlayUrl(torrentData.url)") &&
        code.includes("AndroidJS.openExternalPlayer(torrentData.url, JSON.stringify(torrentData))");

      // Keep the normal Lampa HTML5 player as fallback for non-torrent playback.
      const upstreamFallback = "    }\n    else launchInner()\n\n    if(data.launch_player) delete data.launch_player";
      const externalFallback = `    }
    else if (
        navigator.userAgent.toLowerCase().indexOf('lampa-android') >= 0 &&
        typeof AndroidJS !== 'undefined' &&
        typeof AndroidJS.openExternalPlayer === 'function' &&
        AndroidJS.openExternalPlayer(data.url, JSON.stringify(data))
    ){
        listener.send('external', data)
    }
    else launchInner()

    if(data.launch_player) delete data.launch_player`;
      const occurrences = code.split(upstreamFallback).length - 1;
      if (occurrences !== 1) {
        throw new Error(
          "The upstream Lampa player fallback changed; review the external-player integration"
        );
      }
      return { code: code.replace(upstreamFallback, externalFallback), map: null };
    }

    if (sourceId !== torrentSourcePath) return null;

    // The upstream warning is intended for Android browsers and Media Station X.
    // This app is already an installed Android APK with its own WebView bridge,
    // but it intentionally keeps Lampa's web platform/player path. Recognize only
    // our private WebView marker so Chrome and MSX still receive the upstream hint.
    const upstreamGuard = "if(navigator.userAgent.toLowerCase().indexOf('android') >= 0 && !Platform.is('android') && !Torserver.gstWork()) return Platform.install('apk')";
    const appGuard = "if(navigator.userAgent.toLowerCase().indexOf('android') >= 0 && navigator.userAgent.toLowerCase().indexOf('lampa-android') < 0 && !Platform.is('android') && !Torserver.gstWork()) return Platform.install('apk')";
    const occurrences = code.split(upstreamGuard).length - 1;
    if (occurrences !== 1) {
      throw new Error(
        "The upstream Lampa Android APK hint changed; review the embedded-shell compatibility patch"
      );
    }
    return { code: code.replace(upstreamGuard, appGuard), map: null };
  }
};

const bundle = await rollup({
  input: path.join(sourceRoot, "src", "app.js"),
  plugins: [
    embeddedAndroidShellCompatibility,
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

// The upstream Gulp plugins task starts Rollup streams but calls done()
// synchronously. Wrap those streams in a Promise in a temporary build-only
// Gulpfile so CI waits for every full plugin bundle and CSS include to finish.
const gulpFilePath = path.join(sourceRoot, "gulpfile.js");
let gulpFileText = await readFile(gulpFilePath, "utf8");
const bubbleStreamPattern = /rollup\(\{\s*input: plgFolder\+name,/;
if (!bubbleStreamPattern.test(gulpFileText)) {
  throw new Error("The upstream Lampa plugin bundler changed; review its build task before proceeding");
}
gulpFileText = gulpFileText.replace(bubbleStreamPattern, match => "return " + match);
const pluginsStart = gulpFileText.indexOf("function plugins(done) {");
const pluginsEnd = gulpFileText.indexOf("\nfunction plugin_sass", pluginsStart);
if (pluginsStart < 0 || pluginsEnd < 0) {
  throw new Error("The upstream Lampa plugins task could not be located");
}
const awaitedPluginsTask = `function plugins() {
    const jobs = [];
    fs.readdirSync(plgFolder).filter(function (file) {
        return fs.statSync(plgFolder + '/' + file).isDirectory();
    }).forEach(folder => {
        const entry = plgFolder + folder + '/' + folder + '.js';
        if (fs.existsSync(entry)) {
            const stream = bubbleFile(folder + '/' + folder + '.js');
            jobs.push(new Promise((resolve, reject) => {
                stream.once('error', reject);
                stream.once('finish', resolve);
            }));
        }

        const cssDir = plgFolder + folder + '/css';
        if (fs.existsSync(cssDir) &&
            fs.readdirSync(cssDir).some(file => file.endsWith('.scss'))) {
            const stream = plugin_sass(plgFolder + folder);
            jobs.push(new Promise((resolve, reject) => {
                stream.once('error', reject);
                stream.once('finish', resolve);
            }));
        }
    });
    return Promise.all(jobs);
}`;
gulpFileText = gulpFileText.slice(0, pluginsStart) +
  awaitedPluginsTask + gulpFileText.slice(pluginsEnd);
const temporaryGulpFile = path.join(sourceRoot, ".lampa-build-gulpfile.js");
const gulpCli = path.join(sourceRoot, "node_modules", "gulp", "bin", "gulp.js");
await writeFile(temporaryGulpFile, gulpFileText, "utf8");
try {
  execFileSync(
    process.execPath,
    [gulpCli, "--gulpfile", temporaryGulpFile, "pack_plugins"],
    { cwd: sourceRoot, stdio: "inherit" }
  );
} finally {
  await rm(temporaryGulpFile, { force: true });
}
const pluginSourceRoot = path.join(sourceRoot, "plugins");
const pluginOutputRoot = path.join(outputRoot, "plugins");
const compiledPluginRoot = path.join(sourceRoot, "dest");
const requiredPluginBundles = [
  "collections/collections.js",
  "dlna/dlna.js",
  "iptv/iptv.js",
  "online/online.js",
  "radio/radio.js",
  "tracks/tracks.js"
];
for (const relative of requiredPluginBundles) {
  await readFile(path.join(compiledPluginRoot, relative));
}
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
const builtAppJs = appJs.toString("utf8");
if (!builtAppJs.includes("lampa-android")) {
  throw new Error("The embedded Android shell compatibility patch is missing from the Lampa bundle");
}
if (!builtAppJs.includes("openExternalPlayer")) {
  throw new Error("The external Android player integration is missing from the Lampa bundle");
}
if (!torrentExternalHandoffPatched || !builtAppJs.includes("openExternalPlayer")) {
  throw new Error("The external Android torrent handoff is missing from the Lampa bundle");
}
if (appJs.length < 100_000 || css.length < 20_000) {
  throw new Error("The built Lampa web assets appear incomplete");
}
console.log("Complete Lampa web assets: app.js=" + appJs.length + ", app.css=" + css.length);
