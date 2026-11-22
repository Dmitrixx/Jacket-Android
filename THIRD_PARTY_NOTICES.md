# Third-party source notices

## Lampa source

The complete Lampa web-application source tree is vendored at
`third_party/lampa-source/`.

- Upstream repository: https://github.com/yumata/lampa-source
- Pinned upstream commit: `e583e42238587b2b85afa9f386e9b41adfc16f35`
- Upstream license file: `third_party/lampa-source/LICENSE` (GNU GPL version 2)
- Upstream project README: `third_party/lampa-source/README.md`

The Lampa source was supplied as `lampa-source-main.zip`. The README,
`package.json`, `gulpfile.js`, `src/app.js`, `src/core/manifest.js`,
`src/core/platform.js`, and `LICENSE` were checked against the pinned
upstream Git blob identifiers before vendoring. The import step copies the
upstream tree verbatim and does not rewrite its copyright or license files.

The upstream `package.json` contains an `ISC` package metadata field while
the repository root contains a GPL-2.0 license. These notices are not
silently reconciled here. Keep the original files and verify the applicable
license for any code or dependency before redistributing a combined build.
Dependencies under `node_modules` are not vendored; their respective licenses
must be reviewed separately.

This directory is an isolated third-party source component. Do not remove
upstream copyright notices or the included license. If Lampa-derived files are
modified, mark the changes and dates in those files and preserve the GPL
requirements that apply to the resulting work.
