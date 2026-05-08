Structural search language coverage matrix

Per-language profile FQN, file type, supports flags, predefined-template count, and pattern contexts — verified against live IntelliJ.

# SSR language coverage

Each row says: which language profile to register in `MatchOptions.setFileType(...)`, which `MatchOptions.dialect = ...` to set if the language has dialects, what kind of replace handler the profile uses, and which optional features (shorten FQN, static import, predefined templates) the profile supports. Counts and "loaded" status come from the live IntelliJ Ultimate 2026.1.1 with the standard plugin set.

> **Plugin dependencies note**: profiles ship with their respective language plugin. The "Bundled" rows below assume the language plugin is installed and enabled in the running IDE. If the Go plugin is not installed, `GoStructuralSearchProfile` will not appear in `EP_NAME.extensionList` — this is expected, not a bug. Run the live-enumeration recipe at the bottom of this article to confirm what is actually loaded.

## Bundled (in IntelliJ community / ultimate)

| Language | Profile FQN | File type | Replace handler | Shorten FQN | Static import | Predefined templates | Pattern contexts |
|---|---|---|---:|---:|---:|---:|---|
| Java | `com.intellij.structuralsearch.JavaStructuralSearchProfile` | `JavaFileType.INSTANCE` | `JavaReplaceHandler` (PSI-aware) | yes | yes | 98 | `default`, `member` |
| Kotlin | `o.j.k.idea.k2.codeinsight.structuralsearch.KotlinStructuralSearchProfile` | `KotlinFileType.INSTANCE` | `KotlinStructuralReplaceHandler` (PSI-aware) | yes (async — see [api-recipe](mcp-steroid://skill/structural-search-api-recipe)) | no | 34 | `default`, `property` |
| Groovy | `com.intellij.structuralsearch.groovy.GroovyStructuralSearchProfile` | `GroovyFileType.GROOVY_FILE_TYPE` | `DocumentBasedReplaceHandler` (text-level) | no | no | 0 | `File`, `Class` |
| JavaScript / TypeScript / JSX / TSX | `com.intellij.structuralsearch.JSStructuralSearchProfile` (+ `JSStructuralSearchProfile2` for ECMA L4 / Flex) | `JavaScriptFileType.INSTANCE` (or `TypeScriptFileType`/`TypeScriptJSXFileType`) — pick the dialect's file type | `DocumentBasedReplaceHandler` | no | no | 13 | (none) |
| Go | `com.goide.structuralsearch.GoStructuralSearchProfile` | `GoFileType.INSTANCE` | `DocumentBasedReplaceHandler` | no | no | 21 | (none) |
| SQL (DataGrip / DBE) | `com.intellij.sql.structuralsearch.SqlStructuralSearchProfile` | `SqlFileType.INSTANCE` (set `dialect` to a specific `SqlLanguageDialectEx`) | `DocumentBasedReplaceHandler` | no | no | 3 | (none) |
| HTML | `com.intellij.structuralsearch.XmlStructuralSearchProfile` (shared) | `HtmlFileType.INSTANCE` | `XmlReplaceHandler` | no | no | shared | (none) |
| XML | `com.intellij.structuralsearch.XmlStructuralSearchProfile` | `XmlFileType.INSTANCE` | `XmlReplaceHandler` | no | no | 10 | (none) |
| JSP | `XmlStructuralSearchProfile` + `JspXmlTagExtractor` extension | `NewJspFileType` (or `JSPX`) | `XmlReplaceHandler` | no | no | uses XML/HTML templates | (none) |
| CSS | `com.intellij.structuralsearch.extenders.CssStructuralSearchProfile` | `CSSFileType.INSTANCE` | `DocumentBasedReplaceHandler` | no | no | 0 | (none) |
| Properties | `com.intellij.lang.properties.structuralsearch.PropertiesStructuralSearchProfile` | `PropertiesFileType.INSTANCE` | `DocumentBasedReplaceHandler` | no | no | 2 | (none) |
| Flex / ActionScript | `JSStructuralSearchProfile2` (shared) | the ECMA L4 file type from the JS plugin | `DocumentBasedReplaceHandler` | no | no | shared with JS | (none) |
| Rust (experiment-gated) | `org.rust.ide.ssr.RsStructuralSearchProfile` | `RsFileType.INSTANCE` | replacement throws — search-only | no | no | yes (when experiment enabled) | (none) |

## Bundled in a third-party plugin

| Language | Profile FQN | Notes |
|---|---|---|
| Scala | `org.jetbrains.plugins.scala.structuralSearch.ScalaStructuralSearchProfile` | Ships in the [intellij-scala](https://github.com/JetBrains/intellij-scala) plugin repo, not in this checkout. Registers on the same `com.intellij.structuralsearch.profile` extension point. Supports both `ScalaLanguage.INSTANCE` and `Scala3Language.INSTANCE`. Available when the Scala plugin is installed. |
| PHP | `com.jetbrains.php.structuralsearch.PhpStructuralSearchProfile` | Ships in the PHP plugin (PhpStorm bundle, or PHP plugin in IDEA Ultimate). Adds PHP-specific predefined templates; type constraints work only on `PhpTypedElement` parents. |

## Third-party engine (NOT IntelliJ `StructuralSearchProfile`)

| Language | Engine | Notes |
|---|---|---|
| C# / .NET (Rider) | ReSharper SSR | Lives at `dotnet/Psi.Features/src/Features/StructuralSearch/`. Does NOT register on `com.intellij.structuralsearch.profile`. Same `$variable$` placeholder concept but a different engine — script filters and predefined-template UI semantics differ. Document separately if you author skill articles for Rider. |

## Not supported (no profile)

Python, Ruby, YAML, JSON, Markdown — no SSR profile in this checkout. The `MatchOptions.setFileType(...)` call would land at `StructuralSearchUtil.getProfileByFileType(...) == null` and the recipe would throw before any match. PyCharm's help page is explicit: *"PyCharm doesn't support structural search and replace for Python at the moment."*

## Choosing a file type at runtime

```
import com.intellij.structuralsearch.StructuralSearchUtil
val profile = StructuralSearchUtil.getProfileByFileType(fileType)
require(profile != null) { "No SSR profile for ${fileType.name}; this language is not supported." }
```

For dialects, set `MatchOptions.dialect` explicitly. Example for Groovy:

```
val ft = GroovyFileType.GROOVY_FILE_TYPE
matchOptions.setFileType(ft)
matchOptions.dialect = ft.language   // resolves the profile via Language, not just FileType
```

## Programmatic enumeration of all profiles

```
import com.intellij.structuralsearch.StructuralSearchProfile
StructuralSearchProfile.EP_NAME.extensionList.forEach { p ->
    val cls = p.javaClass
    println("${cls.name}  shortenFQN=${p.supportsShortenFQNames()} staticImport=${p.supportsUseStaticImports()}")
}
```

This is the single most useful diagnostic when an agent suspects a language profile is missing — the EP list is the source of truth.

## Cross-references

- [Canonical API recipe](mcp-steroid://skill/structural-search-api-recipe) — how to feed a `LanguageFileType` to `MatchOptions` and run the search/replace
- [Template language and macros](mcp-steroid://skill/structural-search-syntax) — pattern variable forms, the nine inline macros, custom `_<extension>` macros
- [Kotlin SSR specifics](mcp-steroid://skill/structural-search-kotlin) — Kotlin's four custom filters, async FQN shortening
