#!/usr/bin/env node
// stdio entry point: `npx -y @qartvelo/ads-mcp`
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { createServer } from './server.js';

const server = createServer();
await server.connect(new StdioServerTransport());
