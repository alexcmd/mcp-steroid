Structural search use-case gallery

Recipe gallery grouped by intent: API migrations, code-style enforcement, refactoring, audit, and overload-safe bulk renames. Each entry is a search/replace pair plus the macros it uses.

# SSR use-case gallery

Every entry below is meant to be dropped into the canonical recipe in [api-recipe](mcp-steroid://skill/structural-search-api-recipe) — set `searchPattern`, optionally `replacePattern`, choose the matching `LanguageFileType.INSTANCE`. Recipes that lose information at the source (a `Result<T>` value, a checked exception, a return value) are flagged search-only by default.

## A — API migration / library swaps

| # | Use case | Lang | Search | Replacement |
|---|---|---|---|---|
| A1 | JUnit3 → JUnit4 `assertEquals` (preserve order) | Java | `junit.framework.Assert.assertEquals('_actual, '_expected);` | `org.junit.Assert.assertEquals($expected$, $actual$);` |
| A2 | `JOptionPane.showMessageDialog(null, msg)` → annotated for follow-up | Java | `JOptionPane.'_show(null, '_msg);` | `//FIXME provide a parent frame\nJOptionPane.$show$(null, $msg$);` |
| A3 | Hamcrest `assertThat(actual, is(expected))` → AssertJ | Java | `org.hamcrest.MatcherAssert.assertThat('_a, org.hamcrest.Matchers.is('_e));` | `org.assertj.core.api.Assertions.assertThat($a$).isEqualTo($e$);` |
| A4 | Guava `Lists.newArrayList()` → JDK | Java | `com.google.common.collect.Lists.newArrayList()` | `new java.util.ArrayList<>()` |
| A5 | `Optional.get()` audit (search-only) | Java | `'_o:[exprtype( java\.util\.Optional<.*> )].get()` | (none) |
| A6 | Kotlin `kotlin.test.assertEquals` → JUnit5 | Kotlin | `kotlin.test.assertEquals('_e, '_a)` | `org.junit.jupiter.api.Assertions.assertEquals($e$, $a$)` |

For Java migrations, set `isToShortenFQN = true` so the replacement's FQNs collapse to short names + imports. For Kotlin migrations on K2 set it to false (asynchronous shortening — see [api-recipe](mcp-steroid://skill/structural-search-api-recipe) §7).

## B — Code-style enforcement (mostly search-only)

| # | Use case | Lang | Search | Replacement |
|---|---|---|---|---|
| B1 | Audit `println` calls (project policy: route via `LoggerFactory`) | Kotlin | `println('_msg)` | search-only |
| B2 | Detect `runCatching{}.onFailure{}` chain (this repo bans it) | Kotlin | `'_x.runCatching { '_body }.onFailure { '_handler }` | search-only — see warning below |
| B3 | Audit `Optional.get()` callsites | Java | `'_o:[exprtype( java\.util\.Optional<.*> )].get()` | search-only |
| B4 | Force trailing-lambda for `forEach` | Kotlin | `'_xs.forEach('_lambda:[exprtype( kotlin\.jvm\.functions\.Function1.* )])` | `$xs$.forEach $lambda$` |
| B5 | Detect `System.out` / `System.err` in non-test sources | Java | `System.'_io:[regex( out|err )].'_call('_args*);` | search-only |
| B6 | Detect `if (x != null) x.f()` nullable abuse | Kotlin | `if ('_x != null) '_x.'_f('_args*)` | `$x$?.$f$($args$)` |

> ⚠️ **B2 caveat**: rewriting `runCatching{}.onFailure{}` to `try/catch` **drops the `Result<T>` return value** of the original chain. If the call site consumes the result (`.getOrElse { … }`, `.getOrThrow()`, `let { result -> … }`), the rewrite is unsafe. Default to **search-only**; promote to a replacement only when you've confirmed the surrounding context discards the result. Same caveat applies to any rewrite that drops a value-producing expression in favour of a statement.

## C — Refactoring patterns

| # | Use case | Lang | Search | Replacement |
|---|---|---|---|---|
| C1 | Anonymous `Runnable` → lambda | Java | `new Runnable() { public void run() { '_body*; } }` | `() -> { $body$; }` |
| C2 | Anonymous Kotlin object → SAM lambda | Kotlin | `object : '_T { override fun '_m('_p*) { '_body* } }` | `$T$ { $p$ -> $body$ }` |
| C3 | Builder collapse: `new B().a(x).b(y).build()` → factory | Java | `new '_B().'_a('_x).'_b('_y).build()` | `Factory.create($x$, $y$)` |
| C4 | `if-else` chain (fixed length) → `switch` (Java 14+) | Java | `if ('_e == 'A) { '_s1; } else if ('_e == 'B) { '_s2; } else { '_s3; }` | `switch ($e$) { case A -> $s1$; case B -> $s2$; default -> $s3$; }` |
| C5 | Remove redundant `<T>` on `new ArrayList<T>()` (Java 7+) | Java | `new java.util.ArrayList<'_T>()` | `new java.util.ArrayList<>()` |
| C6 | `someProperty.let { it.f() }` → `someProperty?.f()` | Kotlin | `'_x.let { it.'_m('_args*) }` | `$x$?.$m$($args$)` |

## D — Audit / compliance (always search-only)

| # | Use case | Lang | Search |
|---|---|---|---|
| D1 | All `System.out.println` call sites | Java | `System.out.'_m('_args*);` |
| D2 | Reflection: `Class.forName(...)` | Java | `Class.forName('_name)` |
| D3 | Reflection: `getDeclaredMethod`/`getDeclaredField` | Java | `'_o:[exprtype( java\.lang\.Class.* )].getDeclaredMethod('_n, '_args*)` |
| D4 | JS `eval(...)` | JavaScript | `eval('_x)` |
| D5 | All `Thread.sleep(...)` | Java/Kotlin | `Thread.sleep('_ms)` |
| D6 | `runBlocking` in production code | Kotlin | `kotlinx.coroutines.runBlocking { '_body* }` |
| D7 | `@Suppress("UNCHECKED_CAST")` annotations | Kotlin | `@Suppress("UNCHECKED_CAST") '_decl` |
| D8 | Calls to a `@Deprecated`-annotated method (script filter) | Java | `'_o.'_m('_args*):[script( "var ref = __context__.methodExpression?.resolve(); ref?.modifierList?.findAnnotation('java.lang.Deprecated') != null" )]` |

## E — Bulk renames where text-find fails

These illustrate why SSR beats `grep`/`sed`: the matcher honours overload resolution and PSI types.

| # | Use case | Lang | Search | Replacement |
|---|---|---|---|---|
| E1 | Rename only the `foo(int)` overload, preserve `foo(String)` | Java | `'_o.foo('_x:[exprtype( int )])` | `$o$.bar($x$)` |
| E2 | Wrap `log.debug(a + b)` in `if (log.isDebugEnabled())` | Java | `'_log.debug('_a + '_b)` | `if ($log$.isDebugEnabled()) $log$.debug($a$ + $b$);` |
| E3 | Rename a Kotlin extension on `String` only | Kotlin | `'_s:[exprtype( kotlin\.String )].oldName()` | `$s$.newName()` |
| E4 | Rewrite usages of a constant excluding its declaration | Java | `'_T.MAX_LEN:[!ref( MyClass\.MAX_LEN )]` | `$T$.maxLen()` |
| E5 | `assertTrue(a.equals(b))` → `assertEquals(b, a)` (overload-safe) | Java | `assertTrue('_a.equals('_b))` | `assertEquals($b$, $a$)` |

## Tips for authoring new templates

1. **Start search-only.** Set `replacePattern = null`, run, count. If the count is wildly off, the pattern is wrong; fix it before any write.
2. **Use the apostrophe form** when authoring programmatically — it's the only form that parses inline `:[…]` constraints. See [syntax](mcp-steroid://skill/structural-search-syntax).
3. **Anchor with types when the textual shape is ambiguous.** `'_o.close()` matches every `close()` call site regardless of receiver type; `'_o:[exprtype( java\.io\.Closeable )].close()` is what you usually want.
4. **Use `[!ref(...)]` to exclude the declaration site** of a symbol you're rewriting — see E4.
5. **Use `[contains(...)]`** on a `__context__`-level constraint when the trigger is "this method body contains a call to X" rather than "this code IS a call to X".
6. **Validate first.** Patterns silently fail without `Matcher.validate(project, options)` (see [api-recipe](mcp-steroid://skill/structural-search-api-recipe) §1).

## Cross-references

- [Canonical API recipe](mcp-steroid://skill/structural-search-api-recipe)
- [Template language and macros](mcp-steroid://skill/structural-search-syntax)
- [Language coverage matrix](mcp-steroid://skill/structural-search-coverage)
- [Kotlin SSR specifics](mcp-steroid://skill/structural-search-kotlin)
