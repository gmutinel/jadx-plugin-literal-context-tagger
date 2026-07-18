# jadx-plugin-literal-context-tagger

A [jadx](https://github.com/skylot/jadx) plugin that tags bare numeric/boolean literal call-site arguments passed to a curated list of well-known Android setter methods (`setAlpha`, `setVisibility`, `setEnabled`, etc.) with their semantic context — even when the literal itself has no identifying name.

## Problem

A code change like `view.setAlpha(0.5f)` → `view.setAlpha(1.0f)` is completely invisible to diff/changelog tooling that only looks for named identifiers (strings, resource IDs, method/class names) — the literal `1.0f` carries no semantic meaning on its own. But the CALL SITE does: "this is the alpha value passed to `View.setAlpha()`" is a meaningful, greppable fact.

## Output

Writes `literal_contexts.json` into the output sources directory (`-d`/`-ds`), one entry per call-site argument that (a) targets a curated Android setter and (b) resolves to a constant literal value:

```json
[
  { "class": "com.example.MainActivity", "method": "onCreate", "callTarget": "setAlpha", "argIndex": 0, "literal": "1.0" }
]
```

## Curated setter allowlist

`setAlpha`, `setVisibility`, `setEnabled`, `setBackgroundColor`, `setTextColor`, `setScaleX`, `setScaleY`, `setTranslationX`, `setTranslationY`, `setDuration`, `setElevation`.

Matched by method name only (not declaring class), since these names are distinctive enough in practice that false positives are rare and acceptable for this use case.

## Usage

```bash
java -cp "jadx-all.jar;jadx-plugin-literal-context-tagger.jar" jadx.cli.JadxCLI input.apk -d output
```

(use `:` instead of `;` on Linux/macOS)
