// Invoke the Minecraft MCP dispatcher when the host has no attached Minecraft tools.
import { dispatch } from 'file:///C:/Users/Ryan/source/repos/mc-agent-harness/tools/mcp-server.mjs';
import fs from 'node:fs';
const request = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const response = await dispatch({jsonrpc:'2.0',id:1,method:'tools/call',params:request});
fs.writeFileSync(process.argv[3], JSON.stringify(response, null, 2));
console.log(JSON.stringify(response));
