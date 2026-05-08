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
| A5 | **Java `Optional.get()` audit** (search-only) | Java | `'_o:[exprtype( ~java\.util\.Optional<.*> )].get()` | (none) |
| A6 | Kotlin `kotlin.test.assertEquals` → JUnit5 | Kotlin | `kotlin.test.assertEquals('_e, '_a)` | `org.junit.jupiter.api.Assertions.assertEquals($e$, $a$)` |

For Java migrations, set `isToShortenFQN = true` so the replacement's FQNs collapse to short names + imports. For Kotlin migrations on K2 set it to false (asynchronous shortening — see [api-recipe](mcp-steroid://skill/structural-search-api-recipe) §7).

## B — Code-style enforcement (mostly search-only)

| # | Use case | Lang | Search | Replacement |
|---|---|---|---|---|
| B1 | Audit `println` calls (project policy: route via `LoggerFactory`) | Kotlin | `println('_msg)` | search-only |
| B2 | Detect `runCatching{}.onFailure{}` chain (this repo bans it) | Kotlin | `runCatching { '_BODY* }.onFailure { '_E -> '_HANDLER* }` (matches receiver-less stdlib calls; see [structural-search-kotlin](mcp-steroid://skill/structural-search-kotlin) for the receiver-bearing variant `'_x.runCatching { … }`) | search-only — see warning below |
| B3 | **Java `Optional.get()` audit** (same as A5; surfaced under Code-Style enforcement) | Java | `'_o:[exprtype( ~java\.util\.Optional<.*> )].get()` | search-only |
| B4 | Force trailing-lambda for `forEach` | Kotlin | `'_xs.forEach('_lambda:[exprtype( ~kotlin\.jvm\.functions\.Function1.* )])` | `$xs$.forEach $lambda$` |
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
| D1 | All `System.out` method calls (any method, any args; named for the common `println` case). When the target file is known, prefer `setScope(LocalSearchScope(findProjectPsiFile("<path>")))` per [api-recipe § Single-file scope](mcp-steroid://skill/structural-search-api-recipe). | Java | `System.out.'_m('_args*);` |
| D2 | Reflection: `Class.forName(...)` | Java | `Class.forName('_name)` |
| D3 | Reflection: `getDeclaredMethod`/`getDeclaredField` | Java | `'_o:[exprtype( ~java\.lang\.Class.* )].getDeclaredMethod('_n, '_args*)` |
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

## K — Kotlin idioms and modernization

These are Kotlin-specific recipes beyond the cross-language entries above. Always
set `setFileType(KotlinFileType.INSTANCE)` and prefer `setRecursiveSearch(true)`
(see [api-recipe](mcp-steroid://skill/structural-search-api-recipe) §4 and
[structural-search-kotlin](mcp-steroid://skill/structural-search-kotlin)). The
Kotlin profile's four custom filters (`_AlsoMatchVal`, `_AlsoMatchVar`,
`_AlsoMatchCompanionObject`, `_MatchCallSemantics`) are documented in the
Kotlin-specific article.

| # | Use case | Search | Replacement |
|---|---|---|---|
| K1 | `MutableList<T>()` constructor → idiomatic factory | `MutableList<'_T>('_capacity)` | `ArrayList<$T$>($capacity$)` (or audit-only when capacity is symbolic) |
| K2 | `arrayOf(x).toList()` → `listOf(x)` | `arrayOf('_args*).toList()` | `listOf($args$)` |
| K3 | Redundant smart-cast: `if (x is Y) (x as Y).method()` → `if (x is Y) x.method()` | `if ('_x is '_Y) ('_x as '_Y).'_m('_args*)` | `if ($x$ is $Y$) $x$.$m$($args$)` |
| K4 | Find every `data class` (audit) | `data class '_C('_props*)` | search-only |
| K5 | `lateinit var` audit (could be `var x: T? = null` instead) | `lateinit var '_x: '_T` | search-only |
| K6 | `lazy { … }` audit, surfacing default `SYNCHRONIZED` mode where `NONE` would suffice | `val '_x by lazy { '_body* }` | search-only (decide per call site) |
| K7 | `@Volatile` field audit | `@Volatile var '_x: '_T = '_init` | search-only |
| K8 | `requireNotNull` / `checkNotNull` audit | `requireNotNull('_x)` (and `checkNotNull('_x)` as a separate template) | search-only |
| K9 | Companion-object call to a `@JvmStatic` candidate: `Foo.Companion.bar()` → `Foo.bar()` | `'_C.Companion.'_m('_args*)` | `$C$.$m$($args$)` |
| K10 | `var x = …` followed by `x = …` (could be one assignment) — needs script filter for adjacency; see [syntax §script(...)](mcp-steroid://skill/structural-search-syntax) | `'_x:[script("/* check next sibling reassigns x */")]` | search-only |
| K11 | `runCatching { … }.getOrNull()` (the safer Result-aware variant; complement to B2) | `runCatching { '_body* }.getOrNull()` | search-only |
| K12 | `apply { … }` where the receiver is unused (could be `also` or eliminated) | `'_x.apply { '_body* }` | search-only — manual review for receiver usage |

Use `_AlsoMatchVal( ENABLED )` / `_AlsoMatchVar( ENABLED )` on `'_x` to widen
val/var matching:

| # | Use case | Search |
|---|---|---|
| K13 | Find vars-or-vals of a given type | `var '_x:[_AlsoMatchVal( ENABLED )]:[exprtype( ~kotlin\.collections\.List<.*> )] = '_init` |
| K14 | All companion + non-companion `object` declarations of `Foo` | `object '_o:[_AlsoMatchCompanionObject( ENABLED )] : '_T:*Foo {}` |
| K15 | Argument-order-insensitive constructor match: `A(b=true, c=0, d=1)` ≡ `A(c=0, d=1, b=true)` | `'_:[_MatchCallSemantics( ENABLED )]( true, 0, 1 )` |

## P — Python: not supported by IntelliJ SSR

PyCharm's help page is explicit: *"PyCharm doesn't support structural search and
replace for Python at the moment."* The `community/python/` source tree contains
no `StructuralSearchProfile`, the `com.intellij.structuralsearch.profile`
extension point has no Python contributor, and `StructuralSearchUtil.getProfileByFileType(PythonFileType.INSTANCE)`
returns null. Setting `MatchOptions.setFileType(PythonFileType.INSTANCE)` then
running the canonical recipe throws because the profile is unresolved.

If you need a structural query against Python, use one of these instead (none of
them are SSR — but they fill the same niche):

| Tool | When to reach for it |
|---|---|
| **`grep` / `ripgrep` with PCRE** | Fast and good enough for shape-only queries (`re.search`, `re.findall` callsites). |
| **Python `ast` module** | Standard library; parses any Python source into a typed AST. Works well as a `steroid_execute_code` Kotlin script that shells out to a `python3 -c "import ast; …"` filter. |
| **[LibCST](https://github.com/Instagram/LibCST)** | Concrete syntax tree; preserves whitespace and comments. Best for actual rewrites (Python's analogue of SSR replace). Use it via `mcpScript` shelling out, not as an in-IDE pattern. |
| **IntelliJ's PSI directly** | If you only need a search (no replace) and you want the IDE's index, write a `steroid_execute_code` Kotlin script that walks `com.jetbrains.python.psi.PyFunction` / `PyCallExpression` / etc. directly. This is NOT SSR but uses the same project model. |

A skill article for in-IDE Python pattern queries via raw PSI is out of scope
here; this gallery only documents the IntelliJ SSR engine.

## F — Class hierarchy queries

Java's `implements` keyword in a class template matches BOTH `implements` and `extends` reference lists in source — see [syntax §"Java `implements` matches both"](mcp-steroid://skill/structural-search-syntax). The `:*Foo` shorthand (or `:[regex( *Foo )]`) widens the match to transitive subtypes.

| # | Use case | Lang | Search |
|---|---|---|---|
| F1 | All classes implementing or extending `Greeting` (direct + transitive) | Java | `class '_C implements '_I:*Greeting {}` |
| F2 | All `Throwable` subclasses (direct + transitive) | Java | `class '_C extends '_T:*Throwable {}` |
| F3 | Classes implementing one of two interfaces | Java | `class '_C implements '_I:[regex( Serializable\|Cloneable )] {}` |
| F4 | Classes annotated with a specific annotation | Java | `@'_A:[regex( javax\.persistence\.Entity )] class '_C {}` |
| F5 | Methods that override a method from a hierarchy | Java | `class '_C { @Override '_T '_m('_p*) { '_body*; } }` |

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
