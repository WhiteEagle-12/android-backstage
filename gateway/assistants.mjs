/** Pick a known assistant icon from a client's name and callback origin. Unknown clients get the generic MCP mark. */
export function assistantIcon(name='',origin='') {
  const text=`${name} ${origin}`.toLowerCase();
  if(/chatgpt\.com|chatgpt/.test(text))return 'chatgpt';
  if(/codex/.test(text))return 'codex';
  if(/cursor/.test(text))return 'cursor';
  if(/claude|anthropic/.test(text))return 'claude';
  if(/copilot|vscode|visual studio|github/.test(text))return 'copilot';
  if(/antigravity/.test(text))return 'antigravity';
  if(/grok|x\.ai/.test(text))return 'grok';
  if(/gemini|google/.test(text))return 'gemini';
  if(/opencode/.test(text))return 'opencode';
  return 'mcp';
}
export const ASSISTANT_ICONS=['chatgpt','codex','cursor','claude','copilot','antigravity','grok','gemini','opencode','mcp'];
