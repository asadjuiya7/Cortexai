// Cortex Developer Agent (free models only, no paid API).
// Reads a GitHub issue, asks a free AI model for small find/replace edits,
// applies them, and opens a pull request. It never merges anything.
import { execSync } from 'node:child_process';
import fs from 'node:fs';

const N = process.env.ISSUE_NUMBER;
const TITLE = process.env.ISSUE_TITLE || '';
const BODY = process.env.ISSUE_BODY || '';
const REQUEST = `${TITLE}\n\n${BODY}`.trim();

const sh = (c) => execSync(c, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
const BLOCKED = /(^\.github\/|license|ai-proxy|worker|keystore|\.jks|\.env|secret|signing|\.keystore|local\.properties)/i;

// ---- model chain: first one that works wins ----
async function openai(url, key, model, messages) {
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + key },
    body: JSON.stringify({ model, messages, temperature: 0.2, response_format: { type: 'json_object' } }),
  });
  const j = await r.json();
  const t = j?.choices?.[0]?.message?.content;
  if (!r.ok || !t) throw new Error(`${model}: ${JSON.stringify(j).slice(0, 200)}`);
  return t;
}
async function gemini(messages) {
  const sys = messages.find((m) => m.role === 'system')?.content || '';
  const contents = messages.filter((m) => m.role !== 'system').map((m) => ({ role: m.role === 'assistant' ? 'model' : 'user', parts: [{ text: m.content }] }));
  const model = process.env.GEMINI_MODEL || 'gemini-3.8-flash';
  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': process.env.GEMINI_API_KEY },
    body: JSON.stringify({ systemInstruction: { parts: [{ text: sys }] }, contents, generationConfig: { responseMimeType: 'application/json', temperature: 0.2 } }),
  });
  const j = await r.json();
  const t = j?.candidates?.[0]?.content?.parts?.map((p) => p.text || '').join('');
  if (!r.ok || !t) throw new Error('gemini: ' + JSON.stringify(j).slice(0, 200));
  return t;
}
const PROVIDERS = [
  process.env.GEMINI_API_KEY && ['gemini', (m) => gemini(m)],
  process.env.GITHUB_TOKEN && ['github-models', (m) => openai('https://models.github.ai/inference/chat/completions', process.env.GITHUB_TOKEN, process.env.GITHUB_MODELS_MODEL || 'openai/gpt-4.1', m)],
  process.env.MISTRAL_API_KEY && ['mistral', (m) => openai('https://api.mistral.ai/v1/chat/completions', process.env.MISTRAL_API_KEY, process.env.MISTRAL_CODE_MODEL || 'codestral-latest', m)],
  process.env.OPENROUTER_API_KEY && ['openrouter', (m) => openai('https://openrouter.ai/api/v1/chat/completions', process.env.OPENROUTER_API_KEY, process.env.OPENROUTER_CODE_MODEL || 'qwen/qwen3-coder:free', m)],
].filter(Boolean);

async function ask(messages) {
  let last = '';
  for (const [name, fn] of PROVIDERS) {
    try {
      const out = await fn(messages);
      const s = out.indexOf('{'), e = out.lastIndexOf('}');
      const parsed = JSON.parse(out.slice(s, e + 1));
      console.log('model used:', name);
      return parsed;
    } catch (err) {
      last = String(err);
      console.log('provider failed:', name, last.slice(0, 160));
    }
  }
  throw new Error('All free models failed. Last error: ' + last);
}

function giveUp(msg) {
  console.error(msg);
  try { sh(`gh issue comment ${N} --body ${JSON.stringify('Cortex agent could not finish this: ' + msg)}`); } catch {}
  process.exit(1);
}

// ---- step 1: choose files ----
const files = sh('git ls-files').split('\n').filter((f) => /\.(kt|kts|xml|js|mjs|html|css|json|gradle)$/.test(f) && !/node_modules|package-lock|\.min\./.test(f) && !BLOCKED.test(f));
if (!PROVIDERS.length) giveUp('No free AI key is configured in GitHub secrets.');

const pick = await ask([
  { role: 'system', content: 'You are a careful senior engineer. Reply with JSON only.' },
  { role: 'user', content: `Project files:\n${files.join('\n')}\n\nChange request:\n${REQUEST}\n\nWhich files (at most 4) must be read and edited? Reply: {"files":["path", ...]}` },
]);
const chosen = (pick.files || []).filter((f) => files.includes(f)).slice(0, 4);
if (!chosen.length) giveUp('Could not work out which files to change.');

// ---- step 2: get edits ----
let ctx = '';
for (const f of chosen) ctx += `\n=== FILE: ${f} ===\n${fs.readFileSync(f, 'utf8')}\n`;
if (ctx.length > 180000) giveUp('Files are too large for the free models. Make the request more specific.');

const plan = await ask([
  { role: 'system', content: 'You are a careful senior engineer editing an Android (Kotlin) / Electron app. You cannot compile, so re-check syntax and imports. Keep changes small; do not remove features. Reply with JSON only.' },
  { role: 'user', content: `Change request:\n${REQUEST}\n\n${ctx}\n\nReply exactly: {"summary":"plain-language explanation","edits":[{"path":"file","find":"EXACT existing text, unique in the file, multi-line ok","replace":"new text"}]}\nRules: "find" must match the file character-for-character and appear exactly once. Include enough surrounding lines to be unique. Never touch license checks, API keys, secrets, signing or the Worker URL.` },
]);
const edits = Array.isArray(plan.edits) ? plan.edits : [];
if (!edits.length) giveUp('The model proposed no edits.');

// ---- apply ----
const cache = {};
for (const ed of edits) {
  if (!chosen.includes(ed.path) || BLOCKED.test(ed.path)) giveUp('Edit targets a file that is not allowed: ' + ed.path);
  cache[ed.path] ??= fs.readFileSync(ed.path, 'utf8');
  const parts = cache[ed.path].split(ed.find);
  if (!ed.find || parts.length !== 2) giveUp(`An edit did not match exactly once in ${ed.path} (matches: ${parts.length - 1}).`);
  cache[ed.path] = parts[0] + ed.replace + parts[1];
}
for (const [p, c] of Object.entries(cache)) fs.writeFileSync(p, c);

// ---- pull request ----
const branch = `cortex/issue-${N}`;
sh('git config user.name "Cortex Agent"');
sh('git config user.email "cortex-agent@users.noreply.github.com"');
sh(`git checkout -b ${branch}`);
sh('git add -A');
sh(`git commit -m ${JSON.stringify('Cortex agent: ' + TITLE.slice(0, 60))}`);
sh(`git push -u origin ${branch}`);
fs.writeFileSync('/tmp/pr.md', `${plan.summary || 'Automatic change.'}\n\nFiles changed: ${Object.keys(cache).join(', ')}\n\nWritten by a free AI model and not compiled. Please review before merging.\n\nCloses #${N}`);
sh(`gh pr create --title ${JSON.stringify('Cortex: ' + TITLE.slice(0, 60))} --body-file /tmp/pr.md --head ${branch}`);
console.log('PR created');
