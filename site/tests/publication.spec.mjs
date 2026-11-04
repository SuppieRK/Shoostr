import { expect, test } from '@playwright/test';
import { readFile, readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const output = fileURLToPath(new URL('../dist/', import.meta.url));
const basePath = process.env.BASE_PATH || '/';
const published = new URL(basePath, process.env.SITE_URL || 'https://suppierk.github.io');

test('published pages keep their links and assets inside the hosting path', async ({ page }) => {
  const files = await readdir(output, { recursive: true });
  const pages = files.filter(file => file.endsWith('.html'));
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

/** @param {import('@playwright/test').Page} page */
async function checkEnglishSelectorAndLayout(page) {
  const selector = page.getByRole('combobox', { name: 'Select language', exact: true });
  await expect(selector).toBeVisible();
  await expect(selector.locator('option')).toHaveText(['English']);
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await selector.focus();
  await expect(selector).toBeFocused();
  const currentURL = page.url();
  await selector.selectOption({ label: 'English' });
  await expect(page).toHaveURL(currentURL);

  const layout = await page.evaluate(() => {
    const title = document.querySelector('.title-wrapper');
    const header = document.querySelector('header.header');
    const heading = document.querySelector('main h1');
    return {
      overflow: document.documentElement.scrollWidth > window.innerWidth + 1,
      clippedTitle: title.scrollWidth > title.clientWidth + 1,
      coveredHeading: heading.getBoundingClientRect().top < header.getBoundingClientRect().bottom,
    };
  });
  expect(layout).toEqual({ overflow: false, clippedTitle: false, coveredHeading: false });
}

for (const width of [1440, 390, 320]) {
  for (const theme of ['light', 'dark']) {
    test(`readers can find and copy a recipe at ${width}px in ${theme} mode`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
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
      await page.getByRole('link', { name: 'Build your first endpoint', exact: true }).first().click();
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
      const copy = page.getByRole('button', { name: /Copy to clipboard/i }).first();
      await copy.focus();
      await expect(copy).toBeFocused();
      await copy.click();
      await expect(page.locator('.expressive-code .feedback').first()).toHaveText('Copied!');

      await expect(page.locator('site-search .pagefind-ui__search-input')).toBeAttached();
      await page.locator('main').getByRole('link', { name: /register HTTP methods/i }).first().click();
      await expect(page).toHaveURL(new RegExp(`${basePath}routing/methods/$`));
      await expect(page.locator('site-search .pagefind-ui__search-input')).toBeAttached();
      await page.locator('main').getByRole('link', { name: /match paths and capture parameters/i }).first().click();
      await expect(page).toHaveURL(new RegExp(`${basePath}routing/patterns/$`));

      await expect(page.locator('site-search .pagefind-ui__search-input')).toBeAttached();
      await page.locator('site-search button').first().click();
      await page.locator('site-search dialog input').first().fill('application/problem+json');
      const result = page.locator('.pagefind-ui__result-link').first();
      await expect(result).toContainText(/problem details/i);
      await result.click();
      await expect(page).toHaveURL(new RegExp(`${basePath}errors/problem-details/`));
      await expect(page.getByRole('heading', { level: 1 })).toContainText(/problem details/i);
      expect(failures).toEqual([]);
    });
  }
}

test('missing pages return a usable 404 page with the correct hosting path', async ({ page }) => {
  const response = await page.goto('missing-publication-page/');
  expect(response.status()).toBe(404);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('404');
  await expect(page.locator('header a').first()).toHaveAttribute('href', basePath);
});
