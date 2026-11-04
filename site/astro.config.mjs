import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import { fileURLToPath } from 'node:url';
import * as pagefind from 'pagefind';

function checked(response) {
  if (response.errors.length) throw new Error(response.errors.join('\n'));
  return response;
}

async function buildSearchIndex({ dir }) {
  try {
    const { index } = checked(await pagefind.createIndex({ includeCharacters: '/+' }));
    if (!index) throw new Error('Pagefind did not create a search index.');
    checked(await index.addDirectory({ path: fileURLToPath(dir) }));
    checked(await index.writeFiles({ outputPath: fileURLToPath(new URL('./pagefind/', dir)) }));
  } finally {
    await pagefind.close();
  }
}

export default defineConfig({
  site: process.env.SITE_URL,
  base: process.env.BASE_PATH || '/',
  trailingSlash: 'always',
  integrations: [
    starlight({
      title: 'Shoostr',
      description: 'Build HTTP applications with explicit Java configuration.',
      defaultLocale: 'root',
      locales: { root: { label: 'English', lang: 'en' } },
      favicon: '/favicon.svg',
      customCss: ['./src/styles/brand.css'],
      expressiveCode: { themes: ['github-dark', 'github-light'] },
      components: {
        Head: './src/components/SearchHead.astro',
        Hero: './src/components/HomepageHero.astro',
        LanguageSelect: './src/components/LanguageSelect.astro',
      },
      sidebar: [
        { label: 'Overview', slug: '' },
        {
          label: 'Start here',
          collapsed: false,
          items: [
            { slug: 'start/first-request' },
            { slug: 'start/libraries' },
          ],
        },
        {
          label: 'Routes',
          collapsed: false,
          items: [
            { slug: 'routing/methods' },
            { slug: 'routing/patterns' },
            { slug: 'routing/groups' },
            { slug: 'routing/conditions' },
          ],
        },
        {
          label: 'Request data',
          collapsed: false,
          items: [
            { slug: 'request/parameters' },
            { slug: 'request/headers-cookies' },
            { slug: 'request/metadata' },
            { slug: 'request/state' },
          ],
        },
        {
          label: 'Responses',
          collapsed: true,
          items: [
            { slug: 'responses/bodies' },
            { slug: 'responses/headers' },
            { slug: 'responses/cookies' },
            { slug: 'responses/redirects' },
          ],
        },
        {
          label: 'Bodies and uploads',
          collapsed: true,
          items: [
            { slug: 'bodies/buffered' },
            { slug: 'bodies/streaming-input' },
            { slug: 'bodies/multipart' },
            { slug: 'bodies/persist-upload' },
          ],
        },
        {
          label: 'JSON and representations',
          collapsed: false,
          items: [
            { slug: 'json/codec' },
            { slug: 'json/negotiation' },
          ],
        },
        {
          label: 'Error handling',
          collapsed: false,
          items: [
            { slug: 'errors/exceptions' },
            { slug: 'errors/statuses' },
            { slug: 'errors/problem-details' },
          ],
        },
        {
          label: 'Files and resources',
          collapsed: true,
          items: [
            { slug: 'resources/static' },
            { slug: 'resources/downloads' },
            { slug: 'resources/caching' },
          ],
        },
        {
          label: 'Request lifecycle',
          collapsed: true,
          items: [
            { slug: 'lifecycle/hooks' },
            { slug: 'lifecycle/shutdown' },
            { slug: 'lifecycle/executors' },
          ],
        },
        {
          label: 'Streaming',
          collapsed: true,
          items: [
            { slug: 'streaming/output' },
            { slug: 'streaming/sse' },
            { slug: 'streaming/sse-objects' },
          ],
        },
        {
          label: 'WebSockets',
          collapsed: true,
          items: [
            { slug: 'websockets/managed' },
            { slug: 'websockets/objects' },
            { slug: 'websockets/native' },
          ],
        },
        {
          label: 'Security',
          collapsed: true,
          items: [
            { slug: 'security/authentication' },
            { slug: 'security/pac4j' },
            { slug: 'security/sessions' },
            { slug: 'security/csrf' },
            { slug: 'security/login-logout' },
            { slug: 'security/cors' },
            { slug: 'security/proxies' },
          ],
        },
        {
          label: 'Observability',
          collapsed: true,
          items: [
            { slug: 'observability/metrics' },
            { slug: 'observability/tracing' },
          ],
        },
        {
          label: 'OpenAPI',
          collapsed: true,
          items: [
            { slug: 'openapi/documents' },
            { slug: 'openapi/ui' },
          ],
        },
        {
          label: 'MCP',
          collapsed: true,
          items: [
            { slug: 'mcp/server' },
            { slug: 'mcp/security' },
            { slug: 'mcp/interactions' },
          ],
        },
        {
          label: 'Application configuration',
          collapsed: true,
          items: [
            { slug: 'application/server-options' },
            { slug: 'application/native-configuration' },
          ],
        },
        {
          label: 'Create an extension',
          collapsed: true,
          items: [
            { slug: 'extensions/route-metadata' },
          ],
        },
      ],
    }),
    {
      name: 'shoostr-search-index',
      hooks: { 'astro:build:done': buildSearchIndex },
    },
  ],
});
