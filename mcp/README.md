# MCP

`io.github.suppierk:shoostr-mcp` adapts the official Java MCP SDK 2.1.0 to
Streamable HTTP. Shoostr supplies explicit HTTP routes, security checks and
transport lifetime; configure tools and application policies through the SDK and
existing libraries. The SDK is not included in `shoostr-core`.

Install the capability and mount it once. Shoostr owns the native server;
`mcp.server()` provides borrowed management access. HTTP executors passed to
`mcp.routes(...)` remain caller-owned, independently of native callback execution.

Consumer recipes cover [catalogs and library composition](../site/src/content/docs/mcp/server.mdx),
[identity and sessions](../site/src/content/docs/mcp/security.mdx), and
[client interactions](../site/src/content/docs/mcp/interactions.mdx).
The [runnable example](../examples/17-mcp/README.md) records interoperability checks.
