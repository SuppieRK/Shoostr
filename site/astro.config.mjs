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
      favicon: '/favicon.svg',
      customCss: ['./src/styles/brand.css'],
      expressiveCode: { themes: ['github-dark', 'github-light'] },
      components: { Head: './src/components/SearchHead.astro' },
      sidebar: [
        { label: 'Overview', slug: '' },
        {
          label: 'Start here',
          items: [{ slug: 'start/libraries' }, { slug: 'start/first-request' }],
        },
        {
          label: 'Configure an application',
          collapsed: false,
          items: [
            { slug: 'application/server-options' },
            { slug: 'application/native-configuration' },
          ],
        },
        {
          label: 'Routing',
          collapsed: false,
          items: [
            { slug: 'routing/methods' },
            { slug: 'routing/patterns' },
            { slug: 'routing/groups' },
            { slug: 'routing/conditions' },
          ],
        },
        {
          label: 'Extensions',
          collapsed: false,
          items: [{ slug: 'extensions/route-metadata' }],
        },
        {
          label: 'Request metadata and state',
          collapsed: false,
          items: [
            { slug: 'request/parameters' },
            { slug: 'request/headers-cookies' },
            { slug: 'request/metadata' },
            { slug: 'request/state' },
          ],
        },
        {
          label: 'Bodies, forms and uploads',
          collapsed: false,
          items: [
            { slug: 'bodies/buffered' },
            { slug: 'bodies/streaming-input' },
            { slug: 'bodies/multipart' },
            { slug: 'bodies/persist-upload' },
          ],
        },
        {
          label: 'Responses',
          collapsed: false,
          items: [
            { slug: 'responses/bodies' },
            { slug: 'responses/headers' },
            { slug: 'responses/cookies' },
            { slug: 'responses/redirects' },
          ],
        },
        {
          label: 'Serialization',
          collapsed: false,
          items: [{ slug: 'json/codec' }, { slug: 'json/negotiation' }],
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
          collapsed: false,
          items: [
            { slug: 'resources/static' },
            { slug: 'resources/downloads' },
            { slug: 'resources/caching' },
          ],
        },
        {
          label: 'Streaming',
          collapsed: false,
          items: [
            { slug: 'streaming/output' },
            { slug: 'streaming/sse' },
            { slug: 'streaming/sse-objects' },
          ],
        },
        {
          label: 'WebSockets',
          collapsed: false,
          items: [
            { slug: 'websockets/managed' },
            { slug: 'websockets/objects' },
            { slug: 'websockets/native' },
          ],
        },
        {
          label: 'Lifecycle',
          collapsed: false,
          items: [
            { slug: 'lifecycle/shutdown' },
            { slug: 'lifecycle/hooks' },
            { slug: 'lifecycle/executors' },
          ],
        },
        {
          label: 'Security',
          collapsed: false,
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
          collapsed: false,
          items: [
            { slug: 'observability/metrics' },
            { slug: 'observability/tracing' },
          ],
        },
        {
          label: 'OpenAPI',
          collapsed: false,
          items: [
            { slug: 'openapi/documents' },
            { slug: 'openapi/ui' },
          ],
        },
        {
          label: 'MCP',
          collapsed: false,
          items: [
            { slug: 'mcp/server' },
            { slug: 'mcp/operations' },
            { slug: 'mcp/security' },
            { slug: 'mcp/interactions' },
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
