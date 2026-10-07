# @qartvelo/ads-mcp

[Model Context Protocol](https://modelcontextprotocol.io) server for
[Qartvelo Ads](https://developers.qartvelo.com). It lets AI coding agents search the developer
docs (bundled, works offline), generate SDK integration code, explain error codes and send
**test-mode** ad requests that are never billed.

```sh
claude mcp add qartvelo-ads -- npx -y @qartvelo/ads-mcp
```

Other clients (Cursor, VS Code, Claude Desktop, Windsurf): command `npx`, args `["-y", "@qartvelo/ads-mcp"]`.
Full guide: https://developers.qartvelo.com/ai/mcp-server/

| Tool | Purpose |
|---|---|
| `search_docs`, `read_doc`, `list_docs` | Docs search and pages as Markdown |
| `generate_integration` | Gradle, manifest, init and per-placement code for Android or React Native |
| `explain_code` | Any SDK error, API error, event rejection or fallback reason |
| `get_app_config` | Test-mode session: placements, fallback units, kill switches |
| `test_ad_request` | Test-mode session plus one ad request, tokens redacted |

Resources: `qartvelo-docs://<page>`. Prompt: `integrate-qartvelo-ads`.

`QARTVELO_ADS_BASE_URL` overrides the API origin (default `https://ads.qartvelo.com/`).

## Develop

```sh
npm ci
npm test            # bundles ../src/content/docs into docs.json, then runs the tests
node src/index.js   # stdio server
```
