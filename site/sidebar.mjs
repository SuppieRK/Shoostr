export default [
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
];
