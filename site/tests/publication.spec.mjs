import {expect, test} from '@playwright/test';
import {readdir, readFile, stat} from 'node:fs/promises';
import {join, sep} from 'node:path';
import process from 'node:process';
import {fileURLToPath} from 'node:url';
import {parseFrontmatter} from '@astrojs/markdown-remark';
import sidebar from '../sidebar.mjs';

const output = fileURLToPath(new URL('../dist/', import.meta.url));
const basePath = process.env.BASE_PATH || '/';
const published = new URL(basePath, process.env.SITE_URL || 'https://suppierk.github.io');

test.beforeEach(async ({page}) => {
    await page.route('https://static.cloudflareinsights.com/beacon.min.js', route =>
        route.fulfill({contentType: 'text/javascript', body: ''}));
});

test('published pages keep their links and assets inside the hosting path', async ({page}) => {
    const files = await readdir(output, {recursive: true});
    const pages = files.filter(file => file.endsWith('.html') && !file.startsWith(`javadoc${sep}`));

    expect(pages.length).toBeGreaterThan(1);
    expect(files).toContain('404.html');
    expect(files).toContain(join('pagefind', 'pagefind.js'));
    expect(files).toContain('sitemap-index.xml');

    for (const file of pages) {
        const html = await readFile(join(output, file), 'utf8');
        const references = await page.evaluate(source => {
            const document = new DOMParser().parseFromString(source, 'text/html');
            // Canonical metadata can name the logical 404 route; Pages serves 404.html instead.
            return [...document.querySelectorAll('[href]:not([rel="canonical"]), [src]')].flatMap(element =>
                ['href', 'src'].map(attribute => element.getAttribute(attribute)).filter(Boolean));
        }, html);
        const pageURL = new URL(file.replaceAll('\\', '/'), published);
        for (const reference of references) {
            const url = new URL(reference, pageURL);
            if (url.origin !== published.origin) continue;

            expect(url.pathname.startsWith(basePath), `${file}: ${reference}`).toBe(true);

            const path = join(output, decodeURIComponent(url.pathname.slice(basePath.length)));
            const destination = url.pathname.endsWith('/') ? join(path, 'index.html') : path;

            expect((await stat(destination)).isFile(), `${file}: ${reference}`).toBe(true);
        }
    }
});

test('readers can find dependencies and Javadocs from the documentation build', async ({page, request}) => {
    await page.goto('./');
    const main = page.locator('main');
    const beforeStarting = main.getByRole('region', {name: 'Before you start'});
    const install = beforeStarting.getByRole('link', {name: 'Install Shoostr with Maven or Gradle', exact: true});
    await expect(install).toBeVisible();
    await expect(beforeStarting.getByRole('link', {name: /Java API reference|GitHub|Maven Central/})).toHaveCount(0);
    await install.click();
    await main.getByRole('link', {name: 'The Java API reference', exact: true}).click();
    await expect(page.getByRole('heading', {level: 1})).toHaveText('Look up the Java API');

    const moduleLinks = await main.getByRole('table').last().getByRole('link').all();
    expect(moduleLinks).toHaveLength(8);
    for (const link of moduleLinks) {
        const index = await request.get(await link.getAttribute('href'));
        expect(index.ok()).toBe(true);
    }
    const integrationTypes = [
        'Pac4j', 'MicrometerMetrics', 'OpenTelemetryTracing', 'OpenApi', 'OpenApiOperation',
        'Mcp', 'McpHttpSecurity', 'McpIdentity', 'McpSessionLimits',
    ];
    for (const type of integrationTypes) {
        const link = main.getByRole('link', {name: type, exact: true});
        const javadoc = await request.get(await link.getAttribute('href'));
        expect(javadoc.ok(), `Javadoc for ${type}`).toBe(true);
        expect(await javadoc.text()).toMatch(new RegExp(`<h1 title="[^"]+ ${type}"`));
    }
    await main.getByRole('link', {name: 'Shoostr', exact: true}).click();
    await expect(page.getByRole('heading', {level: 1})).toHaveText('Class Shoostr');
});

test('the agent index describes every guide and links to a complete page-sized export', async ({request}) => {
    const agentIndex = await request.get('llms.txt');
    expect(agentIndex.ok()).toBe(true);
    const index = await agentIndex.text();
    expect(index).toContain('> Shoostr is a Java 21 web framework on Jetty for HTTP APIs.');
    const entries = index.split('\n').filter(line => line.includes(' [Markdown]('));
    expect(entries[0]).toContain(new URL('start/libraries/', published).href);
    expect(entries[1]).toContain(`(${published})`);

    const complete = await request.get('llms-full.txt');
    const abridged = await request.get('llms-small.txt');
    expect(complete.ok()).toBe(true);
    expect(abridged.ok()).toBe(true);
    const full = await complete.text();
    const small = await abridged.text();
    expect(index).toContain(`(${new URL('llms-full.txt', published)}): ${Buffer.byteLength(full).toLocaleString('en-US')} bytes`);
    expect(index).toContain(`(${new URL('llms-small.txt', published)}): ${Buffer.byteLength(small).toLocaleString('en-US')} bytes`);

    const docs = new URL('../src/content/docs/', import.meta.url);
    const guides = (await readdir(docs, {recursive: true})).filter(file => file.endsWith('.mdx'));
    expect(entries).toHaveLength(guides.length);
    const sources = new Set();
    for (const file of guides) {
        const slug = file.slice(0, -4).replaceAll(sep, '/');
        const canonical = new URL(slug === 'index' ? './' : `${slug}/`, published);
        const markdownPath = `llms/${slug}.md`;
        const {frontmatter} = parseFrontmatter(await readFile(new URL(file.replaceAll(sep, '/'), docs), 'utf8'));
        const markdown = await readFile(join(output, markdownPath), 'utf8');
        const topic = sidebar.find(group => group.items?.some(item => item.slug === slug))?.label ?? 'Start here';
        const section = index.split('\n## ').find(section => section.startsWith(`${topic}\n`));

        expect(section).toContain(`- [${frontmatter.title}](${canonical}): ${frontmatter.description} [Markdown](${new URL(markdownPath, published)})`);
        expect(markdown).toContain(`\n\nSource: ${canonical}\n\n`);
        expect(full).toContain(markdown.trimEnd());
        sources.add(`Source: ${canonical}`);
    }
    for (const text of [full, small]) {
        const sourceLines = text.split('\n').filter(line => line.startsWith('Source: '));
        expect(sourceLines).toHaveLength(guides.length);
        expect(new Set(sourceLines)).toEqual(sources);
        expect(sourceLines.slice(0, 2)).toEqual([`Source: ${new URL('start/libraries/', published)}`, `Source: ${published}`]);
    }

    const home = await request.get('llms/index.md');
    expect(home.ok()).toBe(true);
    const homeMarkdown = await home.text();
    expect(homeMarkdown).toContain('# Your HTTP API, written in plain Java.');
    expect(homeMarkdown).toContain('## Before you start');
    expect(homeMarkdown).toContain('routes.get("/hello", (req, res) -> res.text("Hi!"))');

    const firstRequest = await request.get('llms/start/first-request.md');
    expect(firstRequest.ok()).toBe(true);
    const text = (await firstRequest.text()).replaceAll('\r\n', '\n').replaceAll(/[ \t]+$/gm, '');
    for (const [scope, name] of [['main', 'HelloExample'], ['test', 'HelloExampleTest']]) {
        const source = await readFile(new URL(
            `../../examples/01-hello/src/${scope}/java/io/github/suppierk/shoostr/example/hello/${name}.java`,
            import.meta.url), 'utf8');
        expect(text).toContain(source.replaceAll('\r\n', '\n').replaceAll(/[ \t]+$/gm, '').trimEnd());
    }
});

test('installation, Javadocs and Markdown use the versions declared in Gradle', async ({page, request}) => {
    await page.goto('start/libraries/');
    const main = page.locator('main');
    await expect(main.getByRole('columnheader', {name: 'Version', exact: true})).toHaveCount(1);
    const modules = ['core', 'http', 'test', 'pac4j', 'micrometer', 'opentelemetry', 'openapi', 'mcp'];
    const versions = new Map(await Promise.all([...modules, 'bom'].map(async module =>
        [module, await expectedVersion(module)])));
    for (const module of modules) {
        const cells = libraryRow(page, module).getByRole('cell');
        await expect.soft(cells.nth(1), `Gradle version for shoostr-${module}`).toHaveText(versions.get(module));
    }

    const lines = await main.locator('.ec-line .code').allTextContents();
    const code = lines.map(line => line.trim()).join('\n');
    const markdown = await request.get('llms/start/libraries.md');
    expect(markdown.ok()).toBe(true);
    const installation = await markdown.text();
    for (const module of ['core', 'test', 'bom']) {
        const version = versions.get(module);
        const coordinate = `io.github.suppierk:shoostr-${module}:${version}`;
        expect.soft(code).toContain(`<artifactId>shoostr-${module}</artifactId>\n<version>${version}</version>`);
        expect.soft(code).toContain(`"${coordinate}"`);
        expect.soft(code).toContain(`'${coordinate}'`);
        expect.soft(installation).toContain(coordinate);
    }
    await expect(main.getByRole('heading', {name: 'Import the BOM with Gradle (Kotlin DSL)', exact: true})).toBeVisible();
    expect.soft(code).toContain(`implementation(platform("io.github.suppierk:shoostr-bom:${versions.get('bom')}"))`);
    await expect(main.getByText('Use the explicit versions above until Maven Central lists BOM version')).toHaveCount(0);

    await page.goto('start/api/');
    for (const module of modules) {
        const version = versions.get(module);
        const link = libraryRow(page, module).getByRole('link');
        await expect.soft(link).toHaveText(version);
        await expect.soft(link).toHaveAttribute('href', `${basePath}javadoc/${module}/${version}/index.html`);
    }
});

function libraryRow(page, module) {
    const name = page.getByRole('cell', {name: `shoostr-${module}`, exact: true});
    return page.locator('main').getByRole('row').filter({has: name});
}

async function expectedVersion(module) {
    const source = await readFile(new URL(`../../${module}/build.gradle`, import.meta.url), 'utf8');
    const declaration = source.split(/\r?\n/).find(line => /^\s*version\s*=/.test(line));
    const version = declaration?.split(/['"]/)[1];
    expect(version, `${module}/build.gradle should declare its version`).toBeTruthy();
    return version;
}

test('the landing page shows the App route registration example', async ({page}) => {
    await page.goto('./');

    const excerpt = page.locator('#first-route pre code');
    await expect(excerpt).toBeVisible();
    const lines = (await excerpt.locator('.ec-line .code').allTextContents()).map(line => line.trimEnd());

    expect(lines).toEqual([
        'import io.github.suppierk.shoostr.Shoostr;',
        '',
        'public class App {',
        '  public static void main(String[] args) {',
        '    new Shoostr()',
        '      .routes(routes ->',
        '        routes.get("/hello", (req, res) -> res.text("Hi!"))',
        '      )',
        '      .start();',
        '  }',
        '}',
    ]);
});

test('the homepage and guides publish search and social metadata', async ({page, request}) => {
    const imageUrl = new URL('shoostr-social.png', published).href;
    const head = page.locator('head');

    for (const {path, title, heading, type} of [
        {path: './', title: 'Java web framework for HTTP APIs', heading: 'Your HTTP API, written in plain Java.', type: 'website'},
        {path: 'mcp/interactions/', title: 'MCP client input and resource subscriptions', heading: 'Request MCP client input and enable resource subscriptions', type: 'article'},
    ]) {
        await page.goto(path);
        await expect(page.getByRole('heading', {level: 1})).toHaveText(heading);
        await expect(page).toHaveTitle(`${title} | Shoostr`);
        await expect(head.locator('title')).toHaveCount(1);
        await expect(head.locator('link[rel="canonical"]')).toHaveAttribute('href', new URL(path, published).href);
        await expect(head.locator('meta[property="og:type"]')).toHaveAttribute('content', type);
        await expect(head.locator('meta[property="og:image"]')).toHaveAttribute('content', imageUrl);
        await expect(head.locator('meta[name="twitter:image"]')).toHaveAttribute('content', imageUrl);
        await expect(head.locator('script[type="application/ld+json"]')).toHaveCount(type === 'website' ? 1 : 0);
        await expect(page.locator('site-search .pagefind-ui__search-input')).toBeAttached();
    }

    const image = await request.get('./shoostr-social.png');
    expect(image.ok()).toBe(true);
    expect(image.headers()['content-type']).toContain('image/png');
    const png = await image.body();
    expect([png.readUInt32BE(16), png.readUInt32BE(20)]).toEqual([1200, 630]);
});

test('following links from the homepage reveals the selected page in a collapsed sidebar group', async ({page}) => {
    const startHere = page.locator('#starlight__sidebar details').filter({
        has: page.getByText('Start here', {exact: true}),
    });
    const summary = startHere.locator('summary');
    const activeGuide = startHere.getByRole('link', {name: 'Look up the Java API', exact: true});
    const install = page.locator('main').getByRole('link', {name: 'Install Shoostr with Maven or Gradle', exact: true});
    const reference = page.locator('main').getByRole('link', {name: 'The Java API reference', exact: true});

    await page.goto('start/first-request/');
    await expect(page.locator('main').getByRole('link', {name: 'Java API reference', exact: true})).toHaveCount(0);
    await summary.click();
    await expect(startHere).toHaveJSProperty('open', false);

    await page.goto('./');
    await install.click();
    await expect(startHere).toHaveJSProperty('open', true);
    await reference.click();
    await expect(activeGuide).toHaveAttribute('aria-current', 'page');
    await expect(activeGuide).toBeVisible({timeout: 2_000});

    await page.evaluate(() => history.replaceState({...history.state, example: 'preserved'}, ''));
    await summary.click();
    expect(await page.evaluate(() => history.state.example)).toBe('preserved');
    await page.reload();
    await expect(startHere).toHaveJSProperty('open', false);
    await page.goBack();
    await page.goForward();
    await expect(startHere).toHaveJSProperty('open', false);

    await page.goto('./');
    await install.click();
    await reference.click();
    await expect(activeGuide).toBeVisible();
});

for (const width of [1440, 390]) {
    test(`the sidebar remembers expanded and collapsed groups at ${width}px`, async ({page}) => {
        await page.setViewportSize({width, height: 850});
        const sidebar = page.locator('#starlight__sidebar');
        const group = name => sidebar.locator('details').filter({
            has: page.getByText(name, {exact: true}),
        });
        const routes = group('Routes');
        const responses = group('Responses');
        const security = group('Security');

        await page.goto('start/first-request/');
        if (width < 800) await page.locator('button.sl-menu-button').click();

        await expect(routes).toHaveJSProperty('open', true);
        await expect(responses).toHaveJSProperty('open', false);
        await expect(security).toHaveJSProperty('open', false);
        await routes.locator('summary').press('Enter');
        await responses.locator('summary').click();

        await page.reload();
        await expect(routes).toHaveJSProperty('open', false);
        await expect(responses).toHaveJSProperty('open', true);

        await page.goto('security/authentication/');
        await expect(routes).toHaveJSProperty('open', false);
        await expect(responses).toHaveJSProperty('open', true);
        await expect(security).toHaveJSProperty('open', true);

        if (width < 800) await page.locator('button.sl-menu-button').click();
        await responses.locator('summary').click();
        await page.goBack();
        await expect(responses).toHaveJSProperty('open', false);
    });
}

for (const {width, path, reducedMotion, input} of [
    {width: 1440, path: './', reducedMotion: 'no-preference', input: 'keyboard'},
    {width: 1440, path: 'lifecycle/hooks/', reducedMotion: 'no-preference', input: 'mouse'},
    {width: 390, path: 'lifecycle/hooks/', reducedMotion: 'reduce', input: 'keyboard'},
]) {
    test(`readers can scroll to the top by ${input} on ${path} at ${width}px`, async ({page}) => {
        await page.setViewportSize({width, height: 850});
        await page.emulateMedia({reducedMotion});
        await page.goto(path);
        const button = page.locator('#scroll-to-top');
        await expect(button).toBeHidden();

        await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
        await expect(page.getByRole('button', {name: 'Scroll to top'})).toBeInViewport();
        if (input === 'mouse') await button.click({timeout: 5_000});
        else await button.press('Enter');

        await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
        await expect(page.locator('main h1')).toBeFocused();
        await expect(button).toBeHidden();
    });
}

for (const width of [1440, 390, 320]) {
    for (const theme of ['light', 'dark']) {
        test(`readers can find and copy a recipe at ${width}px in ${theme} mode`, async ({page}) => {
            await page.setViewportSize({width, height: 900});
            const failures = [];
            page.on('pageerror', error => failures.push(error.message));
            page.on('response', response => {
                if (response.status() >= 400) failures.push(`${response.status()}: ${response.url()}`);
            });

            await page.goto('./');
            // Starlight initializes search on idle; finish initialization before leaving each page.
            await expect(page.locator('site-search .pagefind-ui__search-input')).toBeAttached();
            await page.locator('starlight-theme-select select:visible').first().selectOption(theme);
            await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
            await page.evaluate(() => history.replaceState(null, '', '?review=language#first-route'));
            await checkEnglishSelectorAndLayout(page);
            await expect(page.locator('.site-title')).toHaveCSS('letter-spacing', 'normal');
            await expect(page.locator('.landing-hero h1')).toHaveCSS('letter-spacing', 'normal');

            await page.getByRole('link', {name: 'Build your first endpoint', exact: true}).first().click();
            await expect(page).toHaveURL(new RegExp(`${basePath}start/first-request/$`));
            await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
                'href', new URL('start/first-request/', published).href);

            if (width < 800) await page.locator('button.sl-menu-button').click();
            await page.locator('starlight-theme-select select:visible').first().selectOption(theme);
            await checkEnglishSelectorAndLayout(page);
            if (width < 800) await page.locator('button.sl-menu-button').click();
            await expect(page.locator('html')).toHaveAttribute('data-theme', theme);

            const content = page.locator('main');
            await expect(content).toContainText('public final class HelloExample');
            await expect(content).toContainText('class HelloExampleTest');

            const copy = page.getByRole('button', {name: /Copy to clipboard/i}).first();
            await copy.focus();
            await expect(copy).toBeFocused();

            await copy.click();
            await expect(page.locator('.expressive-code .feedback').first()).toHaveText('Copied!');

            await expect(page.locator('site-search .pagefind-ui__search-input')).toBeAttached();
            await page.locator('main').getByRole('link', {name: /register HTTP methods/i}).first().click();
            await expect(page).toHaveURL(new RegExp(`${basePath}routing/methods/$`));
            await expect(page.locator('site-search .pagefind-ui__search-input')).toBeAttached();
            await page.locator('main').getByRole('link', {name: /match paths and capture parameters/i}).first().click();
            await expect(page).toHaveURL(new RegExp(`${basePath}routing/patterns/$`));

            await expect(page.locator('site-search .pagefind-ui__search-input')).toBeAttached();
            await page.locator('site-search button').first().click();
            await page.locator('site-search dialog input').first().fill('application/problem+json');
            const result = page.locator('.pagefind-ui__result-link').first();
            await expect(result).toContainText(/problem details/i);
            await result.click();
            await expect(page).toHaveURL(new RegExp(`${basePath}errors/problem-details/`));
            await expect(page.getByRole('heading', {level: 1})).toContainText(/problem details/i);

            expect(failures).toEqual([]);
        });
    }
}

test('missing pages return a usable 404 page with the correct hosting path', async ({page}) => {
    const response = await page.goto('missing-publication-page/');

    expect(response.status()).toBe(404);

    await expect(page.getByRole('heading', {level: 1})).toContainText('404');
    await expect(page.locator('header a').first()).toHaveAttribute('href', basePath);
});

/** @param {import('@playwright/test').Page} page */
async function checkEnglishSelectorAndLayout(page) {
    const selector = page.getByRole('combobox', {name: 'Select language', exact: true});
    await expect(selector).toBeVisible();
    await expect(selector.locator('option')).toHaveText(['English']);
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');

    await selector.focus();
    await expect(selector).toBeFocused();

    const currentURL = page.url();
    await selector.selectOption({label: 'English'});
    await expect(page).toHaveURL(currentURL);

    const layout = await page.evaluate(measurePageLayout);

    expect(layout).toEqual({overflow: false, clippedTitle: false, coveredHeading: false});
}

function measurePageLayout() {
    const title = document.querySelector('.title-wrapper');
    const header = document.querySelector('header.header');
    const heading = document.querySelector('main h1');

    return {
        overflow: document.documentElement.scrollWidth > window.innerWidth + 1,
        clippedTitle: title.scrollWidth > title.clientWidth + 1,
        coveredHeading: heading.getBoundingClientRect().top < header.getBoundingClientRect().bottom,
    };
}
