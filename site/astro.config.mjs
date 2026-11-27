import {defineConfig} from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLlmsTxt from 'starlight-llms-txt';
import {fileURLToPath} from 'node:url';
import * as pagefind from 'pagefind';
import {loadDependencyVersions} from './dependency-versions.mjs';
import {publishJavadoc, serveJavadoc} from './publish-javadoc.mjs';

const site = process.env.SITE_URL;
const base = process.env.BASE_PATH || '/';
const socialImagePath = `${base.replace(/\/$/, '')}/shoostr-social.png`;
const socialImage = site ? new URL(socialImagePath, site).href : socialImagePath;
const socialImageAlt = 'Shoostr, a Java web framework for HTTP APIs, with a Java route example.';
const siteRoot = site && new URL(`${base.replace(/\/$/, '')}/`, site);

async function buildSearchIndex({dir}) {
    try {
        const {index} = checked(await pagefind.createIndex({includeCharacters: '/+'}));
        if (!index) throw new Error('Pagefind did not create a search index.');

        checked(await index.addDirectory({path: fileURLToPath(dir)}));

        checked(await index.writeFiles({outputPath: fileURLToPath(new URL('./pagefind/', dir))}));
    } finally {
        await pagefind.close();
    }
}

function checked(response) {
    if (response.errors.length) throw new Error(response.errors.join('\n'));
    return response;
}

function shoostrReleases() {
    let releases;
    const gradleFiles = [];
    return {
        name: 'shoostr-releases',
        hooks: {
            'astro:config:setup': async ({addWatchFile, command, updateConfig}) => {
                if (command !== 'dev' && command !== 'build') return;
                releases = await loadDependencyVersions(path => {
                    addWatchFile(path);
                    gradleFiles.push(fileURLToPath(path));
                });
                updateConfig({vite: {define: {SHOOSTR_RELEASES: JSON.stringify(releases)}}});
            },
            'astro:build:done': options => publishJavadoc(options, releases.libraries),
            'astro:server:setup': options => {
                options.server.watcher.add(gradleFiles);
                serveJavadoc(options, base, releases.libraries);
            },
        },
    };
}

export default defineConfig({
    site,
    base,
    trailingSlash: 'always',
    integrations: [
        starlight({
            title: 'Shoostr',
            description: 'Build HTTP applications with explicit Java configuration.',
            defaultLocale: 'root',
            locales: {root: {label: 'English', lang: 'en'}},
            favicon: '/favicon.svg',
            plugins: siteRoot ? [
                starlightLlmsTxt({
                    promote: ['start/libraries', 'start/api', 'start/first-request'],
                    details: [
                        '## Start here',
                        `- [Install Shoostr with Maven or Gradle](${new URL('start/libraries/', siteRoot)}): Java requirements, coordinates and dependency versions.`,
                        `- [Java API reference](${new URL('start/api/', siteRoot)}): type lookup and released Javadocs.`,
                        `- [Handle your first HTTP request](${new URL('start/first-request/', siteRoot)}): complete application and HTTP test.`,
                    ].join('\n'),
                }),
            ] : [],
            social: [
                {icon: 'github', label: 'GitHub', href: 'https://github.com/SuppieRK/Shoostr'},
                {icon: 'seti:maven', label: 'Maven Central', href: 'https://central.sonatype.com/artifact/io.github.suppierk/shoostr-core'},
            ],
            head: [
                {tag: 'meta', attrs: {property: 'og:image', content: socialImage}},
                {tag: 'meta', attrs: {property: 'og:image:type', content: 'image/png'}},
                {tag: 'meta', attrs: {property: 'og:image:width', content: '1200'}},
                {tag: 'meta', attrs: {property: 'og:image:height', content: '630'}},
                {tag: 'meta', attrs: {property: 'og:image:alt', content: socialImageAlt}},
                {tag: 'meta', attrs: {name: 'twitter:image', content: socialImage}},
                {tag: 'meta', attrs: {name: 'twitter:image:alt', content: socialImageAlt}},
                {
                    tag: 'script',
                    attrs: {
                        type: 'module',
                        src: 'https://static.cloudflareinsights.com/beacon.min.js',
                        'data-cf-beacon': JSON.stringify({token: '0936c0fc0abd4ecbbc6e3bbe1d0051c4'}),
                    },
                },
            ],
            customCss: ['./src/styles/brand.css'],
            components: {
                Head: './src/components/SearchHead.astro',
                Hero: './src/components/HomepageHero.astro',
                LanguageSelect: './src/components/LanguageSelect.astro',
                Sidebar: './src/components/Sidebar.astro',
                PageFrame: './src/components/PageFrame.astro',
            },
            sidebar: [
                {label: 'Overview', slug: ''},
                {
                    label: 'Start here',
                    collapsed: false,
                    items: [
                        {slug: 'start/libraries'},
                        {slug: 'start/first-request'},
                        {slug: 'start/api'},
                    ],
                },
                {
                    label: 'Routes',
                    collapsed: false,
                    items: [
                        {slug: 'routing/methods'},
                        {slug: 'routing/patterns'},
                        {slug: 'routing/groups'},
                        {slug: 'routing/conditions'},
                    ],
                },
                {
                    label: 'Request data',
                    collapsed: false,
                    items: [
                        {slug: 'request/parameters'},
                        {slug: 'request/headers-cookies'},
                        {slug: 'request/metadata'},
                        {slug: 'request/state'},
                    ],
                },
                {
                    label: 'Responses',
                    collapsed: true,
                    items: [
                        {slug: 'responses/bodies'},
                        {slug: 'responses/headers'},
                        {slug: 'responses/cookies'},
                        {slug: 'responses/redirects'},
                    ],
                },
                {
                    label: 'Bodies and uploads',
                    collapsed: true,
                    items: [
                        {slug: 'bodies/buffered'},
                        {slug: 'bodies/streaming-input'},
                        {slug: 'bodies/multipart'},
                        {slug: 'bodies/persist-upload'},
                    ],
                },
                {
                    label: 'JSON and representations',
                    collapsed: false,
                    items: [
                        {slug: 'json/codec'},
                        {slug: 'json/negotiation'},
                    ],
                },
                {
                    label: 'Error handling',
                    collapsed: false,
                    items: [
                        {slug: 'errors/exceptions'},
                        {slug: 'errors/statuses'},
                        {slug: 'errors/problem-details'},
                    ],
                },
                {
                    label: 'Files and resources',
                    collapsed: true,
                    items: [
                        {slug: 'resources/static'},
                        {slug: 'resources/downloads'},
                        {slug: 'resources/caching'},
                    ],
                },
                {
                    label: 'Request lifecycle',
                    collapsed: true,
                    items: [
                        {slug: 'lifecycle/hooks'},
                        {slug: 'lifecycle/executors'},
                        {slug: 'lifecycle/stream-admission'},
                        {slug: 'lifecycle/shutdown'},
                    ],
                },
                {
                    label: 'Streaming',
                    collapsed: true,
                    items: [
                        {slug: 'streaming/output'},
                        {slug: 'streaming/sse'},
                        {slug: 'streaming/sse-objects'},
                    ],
                },
                {
                    label: 'WebSockets',
                    collapsed: true,
                    items: [
                        {slug: 'websockets/managed'},
                        {slug: 'websockets/objects'},
                        {slug: 'websockets/native'},
                    ],
                },
                {
                    label: 'Security',
                    collapsed: true,
                    items: [
                        {slug: 'security/authentication'},
                        {slug: 'security/pac4j'},
                        {slug: 'security/sessions'},
                        {slug: 'security/csrf'},
                        {slug: 'security/login-logout'},
                        {slug: 'security/cors'},
                        {slug: 'security/proxies'},
                    ],
                },
                {
                    label: 'Observability',
                    collapsed: true,
                    items: [
                        {slug: 'observability/metrics'},
                        {slug: 'observability/tracing'},
                    ],
                },
                {
                    label: 'OpenAPI',
                    collapsed: true,
                    items: [
                        {slug: 'openapi/documents'},
                        {slug: 'openapi/ui'},
                    ],
                },
                {
                    label: 'MCP',
                    collapsed: true,
                    items: [
                        {slug: 'mcp/server'},
                        {slug: 'mcp/security'},
                        {slug: 'mcp/interactions'},
                    ],
                },
                {
                    label: 'Application configuration',
                    collapsed: true,
                    items: [
                        {slug: 'application/server-options'},
                        {slug: 'application/native-configuration'},
                    ],
                },
                {
                    label: 'Create an extension',
                    collapsed: true,
                    items: [
                        {slug: 'extensions/route-metadata'},
                    ],
                },
            ],
        }),
        {
            name: 'shoostr-search-index',
            hooks: {'astro:build:done': buildSearchIndex},
        },
        shoostrReleases(),
    ],
});
