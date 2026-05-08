Structural search canonical API recipe for steroid_execute_code

Validation-first recipe, threading rules, scope and injected-code caveats, smart-pointer invalidation, and a worked end-to-end example.

# Canonical SSR recipe for `steroid_execute_code`

This is the load-bearing article. Any rewrite copied from elsewhere should be cross-checked against the rules below — they catch the most common ways an SSR script silently fails.

## The recipe

```
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.MatchResult
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.plugin.replace.ReplaceOptions
import com.intellij.structuralsearch.plugin.replace.impl.Replacer
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink

// ---- INPUTS — parameterise per task ----
val fileType: LanguageFileType = JavaFileType.INSTANCE
val searchPattern  = "System.out.println('_msg:[exprtype( java\\.lang\\.String )]);"
val replacePattern: String? = "LOG.info(\$msg\$);"   // null → search-only (always start here)
val scope: SearchScope = GlobalSearchScope.projectScope(project)
val reformat = true
val shortenFqNames = true
val searchInjectedCode = false                        // explicit; default true broadens scans

// ---- BUILD ----
val matchOptions = MatchOptions().apply {
    fillSearchCriteria(searchPattern)
    setFileType(fileType); dialect = fileType.language
    setRecursiveSearch(true); setScope(scope)
    setSearchInjectedCode(searchInjectedCode)
}
val replaceOptions = replacePattern?.let {
    ReplaceOptions(matchOptions).apply {
        replacement = it
        isToReformatAccordingToStyle = reformat
        isToShortenFQN = shortenFqNames
    }
}

// ---- VALIDATE FIRST — non-negotiable ----
readAction { Matcher.validate(project, matchOptions) }
if (replaceOptions != null) readAction { Replacer.checkReplacementPattern(project, replaceOptions) }

// ---- SEARCH — Matcher manages its own read action; do NOT wrap ----
val sink = CollectingMatchResultSink()
Matcher(project, matchOptions).findMatches(sink)

// ---- REPORT ----
readAction {
    sink.matches.forEach { m: MatchResult ->
        val el = m.match ?: return@forEach
        val pf = el.containingFile ?: return@forEach
        val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
        val line = (doc?.getLineNumber(el.textRange.startOffset) ?: 0) + 1
        printJson(mapOf("path" to pf.virtualFile?.path, "line" to line,
                        "text" to el.text.lineSequence().first().take(160)))
    }
}
println("attempted=${sink.matches.size}")

// ---- REPLACE — only when applicable ----
if (replaceOptions != null) {
    val replacer = Replacer(project, replaceOptions)
    var replaced = 0; var skipped = 0
    val infos = readAction {
        sink.matches.mapNotNull { m ->
            val target = m.match ?: run { skipped++; return@mapNotNull null }
            val vf = target.containingFile?.virtualFile
            if (vf == null || !vf.isWritable) { skipped++; null } else replacer.buildReplacement(m)
        }
    }
    writeAction {
        CommandProcessor.getInstance(project).executeCommand(project, {
            infos.forEach { info ->
                try { replacer.replace(info); replaced++ }
                catch (e: Exception) { skipped++ }
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }, "SSR Replace", null)
    }
    println("replaced=$replaced skipped=$skipped (of ${sink.matches.size} matches)")
}
```

## Why each step matters

### 1. Validate first — `Matcher.validate(project, options)`

`Matcher(project, options)` constructs the matcher with `checkForErrors=false`. Several `MalformedPatternException` paths inside `PatternCompiler` are silently swallowed when this flag is false. Skipping validation does not raise an error — it produces a quietly-empty match set that looks like a clean miss.

The empirical proof: `Matcher(project, malformed)` succeeds; `Matcher.validate(project, malformed)` raises `MalformedPatternException`. Same options, different code path. Always call `Matcher.validate(project, opts)` (a static method) **before** calling `findMatches(...)` — the constructor itself does not validate.

**Diagnostic for "silent zero matches"** — if `validate` passes but `findMatches` returns 0 against a scope you know contains the shape, dump the `MatchVariableConstraint` fields before assuming the matcher is broken. The most common cause is a missing `~` prefix on an `exprtype(...)` whose argument contains regex metacharacters (`.*`, `+`, `<.*>`, etc.) — without `~`, the constraint is an exact-FQN compare that can't match parameterized types. See the [syntax article](mcp-steroid://skill/structural-search-syntax) "Common pitfall" callout. Quick check:

```
opts.variableConstraintNames.forEach { name ->
    val c = opts.getVariableConstraint(name)
    println("'$name' regex='${c.regExp}' exprType='${c.expressionTypes}' nameOfExprType='${c.nameOfExprType}'")
}
```

If `expressionTypes` contains `<.*>` and `nameOfExprType` is empty, you forgot `~`.

### 2. Don't wrap `findMatches` in an outer `readAction`

`Matcher.findMatches(sink)` wraps its workload in `PsiManager.runInBatchFilesMode { … }` and runs the indexable-files iteration under `ReadAction.runBlocking` itself. An outer read action causes nested locks and, on `GlobalSearchScope`, can deadlock the indexed-files iteration. The same advice applies to `LocalSearchScope` in production code; only the `testFindMatches(...)` API skips the read-action scheduler. If you need a read action to *prepare* a `LocalSearchScope` (e.g. to collect `PsiElement`s), release it before calling `findMatches`.

### 3. From a coroutine: prefer `Replacer.replace(info)` (singular) over `replaceAll`

`replaceAll` enters `runWriteActionWithCancellableProgressInDispatchThread` — it is designed for the modal SSR dialog and will deadlock the EDT when invoked from `mcpScript`. The singular `replace(info)` is what `SSBasedInspection`'s quick-fix uses and what the Kotlin K2 replace tests use.

### 4. One `CommandProcessor.executeCommand` block

Wrap the whole batch of `replacer.replace(info)` calls in a single `CommandProcessor.getInstance(project).executeCommand(project, { ... }, "SSR Replace", null)`. That gives the user a single Ctrl+Z that undoes the entire batch.

### 5. `searchInjectedCode = false` for bulk edits

The default is `true`. With it true, `Matcher` recursively enumerates injected PSI from each `PsiLanguageInjectionHost` — meaning a SQL/RegExp/Java SSR can run inside `@Language("SQL") String sql = "..."` fragments embedded in Java/Kotlin string hosts, not just over standalone files. This is great for finding bugs in injected fragments and almost always wrong for bulk identifier rewrites. Set it to false explicitly unless injected code is intended.

### 6. Match-lifetime safety — `SmartPsiElementPointer`s can invalidate

`MatchResult` and `ReplacementInfo` rely on `SmartPsiElementPointer`s. When the matched element is deleted (by an earlier replacement, or by an unrelated edit between search and replace), `m.match` returns null. `Replacer.doReplace` silently returns null for null/non-writable/invalid targets. A naive `replaced=${sink.matches.size}` tally is therefore optimistic. Build `ReplacementInfo`s before any write, filter out null/non-writable targets up front, and report attempted vs replaced vs skipped separately.

### 7. Kotlin FQN shortening is asynchronous

`KotlinStructuralReplaceHandler.postProcess` submits a non-blocking `analyze { collectPossibleReferenceShorteningsInElementForIde(...) }`, then `invokeShortening()` runs on the EDT under a separate write command. **A script that reads file contents immediately after `replace(info)` may still see fully-qualified names.** For deterministic Kotlin scripts, set `isToShortenFQN = false` and run shortening explicitly afterwards.

### 8. Scope sizing and dry-runs

`Matcher` queues one task per indexable file for global scopes. Before a project-wide rewrite, run the recipe with `replacePattern = null` to count matches; consider `LocalSearchScope`, module/production scope, or chunking by content root. Don't invent a hard threshold — a quick dry-run answers the question for the actual project.

## Knobs cheat-sheet

| Knob | Type | Default | Notes |
|---|---|---|---|
| `MatchOptions.looseMatching` | bool | true | Relaxes whitespace/structure matching |
| `MatchOptions.recursiveSearch` | bool | false | When true, `findMatches` also descends into matched subtrees |
| `MatchOptions.caseSensitiveMatch` | bool | false | Identifier case-sensitivity |
| `MatchOptions.searchInjectedCode` | bool | **true** | Set to false for bulk edits |
| `MatchOptions.dialect` | Language | fileType.language | Set explicitly for languages with dialects (Groovy, JS) |
| `ReplaceOptions.isToReformatAccordingToStyle` | bool | persisted-default true | Reformats a few lines around each replacement |
| `ReplaceOptions.isToShortenFQN` | bool | persisted-default true | Java/Kotlin only; K2 is async (see §7) |
| `ReplaceOptions.isToUseStaticImport` | bool | false | Java only |

## What NOT to do

```
// BROKEN — silently overwrites the search pattern
val cfg = SearchConfiguration().apply {
    matchOptions.fillSearchCriteria(search)        // searchPattern = compiled SEARCH
}
val replaceOptions = ReplaceConfiguration(cfg).replaceOptions.apply {
    StringToConstraintsTransformer.transformCriteria(replace, cfg.matchOptions)  // OVERWRITES
    replacement = cfg.matchOptions.searchPattern
}
val sink = CollectingMatchResultSink().also { Matcher(project, cfg.matchOptions).findMatches(it) }
//                                                              ^^^ now searching for the REPLACE template
```

`StringToConstraintsTransformer.transformCriteria` writes back into `options.searchPattern`. Calling it twice on the same options clobbers the first call. The empirical fingerprint: search returns zero matches, or the wrong matches. Always set `replacement` directly to dollar-form text on `ReplaceOptions` — never re-run the transformer on populated options.

## Cross-references

- [Template language and macros](mcp-steroid://skill/structural-search-syntax) — what to put in `searchPattern`
- [Language coverage matrix](mcp-steroid://skill/structural-search-coverage) — which `LanguageFileType.INSTANCE` to use
- [Use-case gallery](mcp-steroid://skill/structural-search-use-cases) — search/replace pairs grouped by intent
- [Kotlin SSR specifics](mcp-steroid://skill/structural-search-kotlin) — K2 async shortening, custom filters, limitations
