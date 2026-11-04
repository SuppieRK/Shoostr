# Edit and review the site

Use Node.js **22.22.0 or later** and a complete repository checkout: documentation pages import Java examples and tests
from outside this folder. Run all commands below from the **repository root**. These commands work in Bash and
PowerShell.

## Edit with live updates

Install dependencies after checkout or when `site/package-lock.json` changes:

```bash
npm --prefix site ci
```

Start the development server:

```bash
npm --prefix site run dev
```

Open the address printed in the terminal, normally <http://127.0.0.1:4321/>. Keep the server running while editing;
saving a page or stylesheet updates the browser. Press **Ctrl+C** to stop it. Use the production preview below to check
search, because development mode does not build the Pagefind search index.

| What to change                   | File or folder, relative to the repository root               |
|----------------------------------|---------------------------------------------------------------|
| Homepage text and layout         | [site/src/content/docs/index.mdx](src/content/docs/index.mdx) |
| Documentation pages              | [site/src/content/docs/](src/content/docs)                    |
| Sidebar grouping and order       | [site/astro.config.mjs](astro.config.mjs)                     |
| Colors, typography and spacing   | [site/src/styles/brand.css](src/styles/brand.css)             |
| Imported Java examples and tests | [examples/](../examples)                                      |

Edit the source files, rather than generated files in `site/dist/`. For Java snippets, edit the imported source in
`examples/` and follow the Java validation requirements in the [contribution guide](../CONTRIBUTING.md).

## Review local changes

List changed files, including untracked files, then inspect the staged and unstaged changes to tracked site files:

```bash
git status --short -- site
git diff HEAD -- site
```

## Check the production build

Stop the development server with **Ctrl+C**, then build the static site and serve it locally to check the generated
pages and search:

```bash
npm --prefix site run build
npm --prefix site run preview
```

Open the printed address, normally <http://127.0.0.1:4321/>. Preview serves the last build; after further edits, stop
preview, rebuild and start preview again.

Check desktop and mobile widths, both light and dark themes, navigation, code copying and a search result.
The [documentation guide](../CONTRIBUTING.md#documentation)
owns the writing rules. Its [build and preview section](../CONTRIBUTING.md#build-and-preview)
covers `/Shoostr/` hosting and the automated browser checks.

## Languages

English is the default language and uses the existing URLs without an `/en/` prefix. The `root` entry
in [site/astro.config.mjs](astro.config.mjs) configures its language tag and label; keep `defaultLocale: 'root'` when
adding other languages.

The [i18n collection](src/content.config.ts) loads optional UI-label overrides from
[site/src/content/i18n/](src/content/i18n). Its `en.json` intentionally contains `{}`
to retain Starlight's built-in English labels. These files change interface labels, such as search and navigation
controls, rather than translating documentation prose.
The [language-selector override](src/components/LanguageSelect.astro) shows English when it is the only language and
uses Starlight's normal switcher for multiple languages.

To add another language, such as French:

1. Add `fr: { label: 'Français', lang: 'fr' }` alongside `root` in `locales`.
2. Add translated topics under `site/src/content/docs/fr/`. For example,
   `site/src/content/docs/start/first-request.mdx` becomes
   `site/src/content/docs/fr/start/first-request.mdx`. Translate the frontmatter and prose while retaining the complete
   examples, tests and essential constraints. Reuse the original Java source imports, adding one `../` for the extra
   directory level, and check relative navigation links.
3. Optionally add `site/src/content/i18n/fr.json` for UI-label overrides. Starlight already supplies French UI defaults.
   Custom sidebar group labels have their own
   `translations` settings in `site/astro.config.mjs`.

Configuring another locale exposes English fallback pages for topics that have not been translated. Those fallback pages
are not completed translations. Update the language-selector checks in `site/tests/publication.spec.mjs` when adding a
language, then run the hosting and browser checks in the documentation guide linked above.
See [Starlight's i18n guide](https://starlight.astro.build/guides/i18n/) for the locale configuration and fallback
model.
