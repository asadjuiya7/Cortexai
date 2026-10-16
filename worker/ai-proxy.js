// ============================================================
// Cortex AI Proxy — paste this whole file into a Cloudflare Worker
// (dashboard.cloudflare.com → Workers & Pages → Create → Edit Code)
//
// This is the ONLY place your Anthropic / OpenAI / Gemini keys live.
// Customers' apps call THIS worker — they never see or enter a key.
// ============================================================

const SUPABASE_URL = 'https://dgjuojkzatgqoanltoah.supabase.co';
const SUPABASE_ANON_KEY =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRnanVvamt6YXRncW9hbmx0b2FoIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODcyMTAwMTUsImV4cCI6MjEwMjc4NjAxNX0.D2JBRymOaNnJJnH2k9TAk3zXMhjozjLfadR7CLXo-sQ';

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
};

const ADMIN_SECRET = 'JUIYASHB0786?';

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json', ...CORS_HEADERS },
  });
}

async function checkLicense(licenseKey) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/check_license_valid`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      apikey: SUPABASE_ANON_KEY,
      Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
    },
    body: JSON.stringify({ p_license_key: licenseKey }),
  });
  return res.json();
}

// ------------------------------------------------------------------
// Backup chain (all optional, each needs its key in Cloudflare Variables):
//   GROQ_API_KEY      -> speech-to-text for voice messages + Llama chat
//   CEREBRAS_API_KEY  -> Llama chat
//   (Workers AI binding named AI) -> Llama chat
// Optional overrides: GROQ_MODEL, GROQ_WHISPER_MODEL, CEREBRAS_MODEL
// ------------------------------------------------------------------
async function transcribeAudio(env, b64) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  const fd = new FormData();
  fd.append('file', new Blob([bytes], { type: 'audio/wav' }), 'audio.wav');
  fd.append('model', env.GROQ_WHISPER_MODEL || 'whisper-large-v3-turbo');
  fd.append('response_format', 'json');
  const res = await fetch('https://api.groq.com/openai/v1/audio/transcriptions', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + env.GROQ_API_KEY },
    body: fd,
  });
  const j = await res.json();
  if (!res.ok || typeof j.text !== 'string') throw new Error('whisper_failed');
  return j.text.trim();
}

async function openaiCompat(url, key, model, chat) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + key },
    body: JSON.stringify({ model, messages: chat, max_tokens: 1800 }),
    signal: AbortSignal.timeout(7000),
  });
  const j = await res.json();
  const t = j && j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content;
  if (!res.ok || !t) throw new Error('chat_failed ' + res.status + ' ' + JSON.stringify(j).slice(0, 120));
  return String(t);
}

// Backup models sometimes write {"open_app":"Chrome"} instead of
// {"device_action":"open_app","param":"Chrome"}. Fix that shape here.
const PHONE_ACTIONS = ['open_app','flashlight_on','flashlight_off','volume_up','volume_down','mute','unmute','call','send_sms','whatsapp_message','send_email','set_alarm','set_timer','web_search','youtube_search','app_search','navigate','open_url','battery_status','wifi_settings','bluetooth_settings','go_home','go_back','recents','notifications','lock_screen','screenshot','click_text','type_text','scroll_down','scroll_up','read_screen'];
function fixActionShape(text) {
  if (typeof text !== 'string') return text;
  let t = text.trim().replace(/^```(?:json)?\s*/i, '').replace(/```\s*$/i, '').trim();
  if (!t.startsWith('{') || !t.endsWith('}')) return text;
  try {
    const o = JSON.parse(t);
    if (o && typeof o === 'object' && !o.device_action) {
      const keys = Object.keys(o);
      const k = (o.action && PHONE_ACTIONS.includes(o.action)) ? 'action' : keys.find((x) => PHONE_ACTIONS.includes(x));
      if (k) {
        const name = k === 'action' ? o.action : k;
        const param = k === 'action' ? (o.param ?? o.value ?? '') : o[k];
        return JSON.stringify({ device_action: name, param: param === true || param == null ? '' : String(param) });
      }
    }
  } catch (e) {}
  return text;
}

// Returns reply text, or null when no backup could answer.
async function fallbackChainRaw(env, system, messages) {
  const plain = [];
  for (const m of messages) {
    const role = m.role === 'assistant' ? 'assistant' : 'user';
    if (!Array.isArray(m.content)) {
      plain.push({ role, content: String(m.content) });
      continue;
    }
    let text = '';
    for (const p of m.content) {
      if (p && p.inlineData && /audio/.test(p.inlineData.mimeType || '')) {
        let said = null;
        if (env.GROQ_API_KEY) {
          try { said = await transcribeAudio(env, p.inlineData.data); } catch (e) {}
        }
        if (said === null && env.AI) {
          try {
            const w = await env.AI.run('@cf/openai/whisper-large-v3-turbo', { audio: p.inlineData.data });
            said = String((w && (w.text || (w.result && w.result.text))) || '').trim();
          } catch (e) {}
        }
        if (said === null) return null; // no way to turn voice into text
        // Nothing intelligible heard: treat as background noise.
        if (said.replace(/[^\p{L}\p{N}]/gu, '').length < 2) return 'IGNORE';
        text += '(The user said by voice:) ' + said + '\n';
      } else if (p && p.text && !/^\(This is the user's voice message/.test(p.text)) {
        text += p.text + '\n';
      }
    }
    plain.push({ role, content: text.trim() || '(no text)' });
  }
  let sys2 = system || '';
  if (!/LIVE DATA/.test(sys2)) sys2 += await liveContext(env, lastUserText(plain));
  const chat = sys2 ? [{ role: 'system', content: sys2 }, ...plain] : plain;

  if (env.CEREBRAS_API_KEY) {
    try {
      return await openaiCompat('https://api.cerebras.ai/v1/chat/completions', env.CEREBRAS_API_KEY, env.CEREBRAS_MODEL || 'llama-3.3-70b', chat);
    } catch (e) {}
  }
  if (env.GROQ_API_KEY) {
    try {
      return await openaiCompat('https://api.groq.com/openai/v1/chat/completions', env.GROQ_API_KEY, env.GROQ_MODEL || 'llama-3.3-70b-versatile', chat);
    } catch (e) {}
  }
  if (env.AI) {
    try {
      const out = await env.AI.run('@cf/meta/llama-3.3-70b-instruct-fp8-fast', { messages: chat, max_tokens: 1800 });
      const t = out && (out.response || (out.result && out.result.response));
      if (t) return typeof t === 'object' ? JSON.stringify(t) : String(t);
    } catch (e) {}
  }
  if (env.MISTRAL_API_KEY) {
    try {
      return await openaiCompat('https://api.mistral.ai/v1/chat/completions', env.MISTRAL_API_KEY, env.MISTRAL_MODEL || 'mistral-small-latest', chat);
    } catch (e) {}
  }
  if (env.GITHUB_MODELS_TOKEN) {
    try {
      return await openaiCompat('https://models.github.ai/inference/chat/completions', env.GITHUB_MODELS_TOKEN, env.GITHUB_MODELS_MODEL || 'openai/gpt-4.1-mini', chat);
    } catch (e) {}
  }
  if (env.NVIDIA_API_KEY) {
    try {
      return await openaiCompat('https://integrate.api.nvidia.com/v1/chat/completions', env.NVIDIA_API_KEY, env.NVIDIA_MODEL || 'meta/llama-3.3-70b-instruct', chat);
    } catch (e) {}
  }
  if (env.OPENROUTER_API_KEY) {
    try {
      return await openaiCompat('https://openrouter.ai/api/v1/chat/completions', env.OPENROUTER_API_KEY, env.OPENROUTER_MODEL || 'meta-llama/llama-3.3-70b-instruct:free', chat);
    } catch (e) {}
  }
  return null;
}

// Temporary checker. Only works while the variable DIAG is set to 1.
async function runDiag(env) {
  const out = {
    keys_set: {
      GEMINI: !!env.GEMINI_API_KEY, GROQ: !!env.GROQ_API_KEY, CEREBRAS: !!env.CEREBRAS_API_KEY,
      MISTRAL: !!env.MISTRAL_API_KEY, OPENROUTER: !!env.OPENROUTER_API_KEY,
      GITHUB_MODELS: !!env.GITHUB_MODELS_TOKEN, NVIDIA: !!env.NVIDIA_API_KEY, CLOUDFLARE_AI: !!env.AI,
    },
    tests: {},
  };
  const chat = [{ role: 'user', content: 'Say OK' }];
  const run = async (name, fn) => {
    try { out.tests[name] = 'OK: ' + String(await fn()).slice(0, 30); }
    catch (e) { out.tests[name] = 'FAIL: ' + String(e && e.message ? e.message : e).slice(0, 160); }
  };
  if (env.GEMINI_API_KEY) await run('gemini', async () => {
    const r = await fetch('https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent', {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY },
      body: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: 'Say OK' }] }] }),
    });
    const j = await r.json();
    if (j.error) throw new Error(j.error.message);
    return j.candidates[0].content.parts[0].text;
  });
  if (env.MISTRAL_API_KEY) await run('mistral', () => openaiCompat('https://api.mistral.ai/v1/chat/completions', env.MISTRAL_API_KEY, env.MISTRAL_MODEL || 'mistral-small-latest', chat));
  if (env.OPENROUTER_API_KEY) await run('openrouter', () => openaiCompat('https://openrouter.ai/api/v1/chat/completions', env.OPENROUTER_API_KEY, env.OPENROUTER_MODEL || 'meta-llama/llama-3.3-70b-instruct:free', chat));
  if (env.GITHUB_MODELS_TOKEN) await run('github_models', () => openaiCompat('https://models.github.ai/inference/chat/completions', env.GITHUB_MODELS_TOKEN, env.GITHUB_MODELS_MODEL || 'openai/gpt-4.1-mini', chat));
  if (env.GROQ_API_KEY) await run('groq', () => openaiCompat('https://api.groq.com/openai/v1/chat/completions', env.GROQ_API_KEY, env.GROQ_MODEL || 'llama-3.3-70b-versatile', chat));
  if (env.CEREBRAS_API_KEY) await run('cerebras', () => openaiCompat('https://api.cerebras.ai/v1/chat/completions', env.CEREBRAS_API_KEY, env.CEREBRAS_MODEL || 'llama-3.3-70b', chat));
  if (env.AI) await run('cloudflare_ai', async () => {
    const o = await env.AI.run('@cf/meta/llama-3.3-70b-instruct-fp8-fast', { messages: chat, max_tokens: 20 });
    const r = o && (o.response || (o.result && o.result.response));
    return typeof r === 'object' ? JSON.stringify(r) : r;
  });
  return out;
}

async function fallbackChain(env, system, messages) {
  const reminder = '\n\nFORMAT RULE: a phone action must be exactly {"device_action":"<action>","param":"<value>"} with those two keys, for example {"device_action":"open_app","param":"Chrome"} or {"device_action":"web_search","param":"Lodhran weather"}.';
  const rule2 = '\nSEARCH RULE: if the user names an app to search in (YouTube, Play Store, Spotify, Daraz, Amazon, Maps, WhatsApp...) output ONE action: {"device_action":"app_search","param":"<app>,<query>"}. Only if no app is named, use {"device_action":"web_search","param":"<query>"}. NEVER open_app first for a search. Never use get_weather. NEVER say "Searching for..." in words: reply with the JSON action itself and nothing else.';
  const r = await fallbackChainRaw(env, (system || '') + reminder + rule2, messages);
  return r === null ? null : (/LIVE DATA/.test(system || '') ? fixActionShape(r) : fixSearch(fixActionShape(r), messages));
}

// If the model only opened Chrome but the user asked to search, turn it into a real search.
function fixSearch(reply, messages) {
  try {
    if (typeof reply !== 'string') return reply;
    const rt = reply.trim();
    // Model said "Searching for X." in words but sent no command: turn it into the command.
    if (!rt.startsWith('{') && rt.length < 140) {
      const sm = rt.match(/^(?:ok(?:ay)?[,.! ]*)?(?:let me |i(?:'ll| will) |i am |i'm )?(?:search(?:ing)?|look(?:ing)? up|checking)(?: for| up)?\s+(.{3,100}?)[.!…]*$/i);
      if (sm) return JSON.stringify({ device_action: 'web_search', param: sm[1].trim() });
    }
    const o = JSON.parse(rt);
    if (!o || typeof o !== 'object') return reply;
    let last = '';
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i];
      if (m.role === 'user') {
        last = Array.isArray(m.content) ? m.content.map((p) => (p && p.text) || '').join(' ') : String(m.content);
        break;
      }
    }
    if (o.get_weather) {
      const city = (last.match(/(?:in|of|for)\s+([A-Za-z ]{3,30})$/i) || [])[1] || '';
      return JSON.stringify({ device_action: 'web_search', param: (city + ' weather today').trim() });
    }
    if (o.device_action === 'open_app' && /chrome|browser/i.test(String(o.param))) {
      const m = last.match(/(?:search(?:\s+for)?|dhoond(?:o)?|dhundo|google)\s+(.{2,80})$/i);
      if (m) return JSON.stringify({ device_action: 'web_search', param: m[1].replace(/[.?!]+$/, '').trim() });
    }
  } catch (e) {}
  return reply;
}


// ---------------------------------------------------------------- live data (web search + weather)
// The worker fetches fresh information itself and hands it to the model, so Cortex answers
// directly instead of just opening Chrome. Weather uses Open-Meteo (free, no key).
// Web search uses Tavily if TAVILY_API_KEY is set (free plan), otherwise DuckDuckGo as best effort.
const WMO = { 0: 'clear sky', 1: 'mostly clear', 2: 'partly cloudy', 3: 'overcast', 45: 'fog', 48: 'fog', 51: 'light drizzle', 53: 'drizzle', 55: 'heavy drizzle', 61: 'light rain', 63: 'rain', 65: 'heavy rain', 71: 'light snow', 73: 'snow', 75: 'heavy snow', 80: 'rain showers', 81: 'rain showers', 82: 'violent showers', 95: 'thunderstorm', 96: 'thunderstorm with hail', 99: 'thunderstorm with hail' };

function lastUserText(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.role !== 'user') continue;
    const t = Array.isArray(m.content) ? m.content.map((p) => (p && p.text) || '').join(' ') : String(m.content || '');
    return t.replace(/^\(The user said by voice:\)\s*/i, '').trim();
  }
  return '';
}

function weatherCity(text) {
  const t = text.replace(/[?.!,]+/g, ' ').replace(/\s+/g, ' ').trim();
  let m = t.match(/(?:weather|mausam|temperature|forecast)\s+(?:in|of|for|at|ka|ki|ke)?\s*([A-Za-z][A-Za-z ]{1,30}?)(?:\s+(?:today|now|tomorrow|aaj|kal|city|ka|ki|ke|kaisa|kesa|hai|please))?$/i);
  if (!m) m = t.match(/([A-Za-z][A-Za-z ]{1,30}?)\s+(?:city\s+)?(?:ka|ki|ke|me|mein|main)?\s*(?:weather|mausam|temperature|forecast)/i);
  if (!m) return '';
  return m[1].replace(/\b(the|city|live|current|today|now|me|mein|main|what|whats|is|how|search|open|chrome|and|please|batao|bata|do|check|karo|kro)\b/gi, ' ').replace(/\s+/g, ' ').trim();
}

async function weatherNow(city) {
  const g = await fetch('https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=' + encodeURIComponent(city), { signal: AbortSignal.timeout(4000) });
  const gj = await g.json();
  const loc = gj && gj.results && gj.results[0];
  if (!loc) return '';
  const url = 'https://api.open-meteo.com/v1/forecast?latitude=' + loc.latitude + '&longitude=' + loc.longitude +
    '&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m' +
    '&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&timezone=auto&forecast_days=3';
  const w = await fetch(url, { signal: AbortSignal.timeout(4000) });
  const wj = await w.json();
  const c = wj.current || {};
  const d = wj.daily || {};
  let out = 'Weather for ' + loc.name + (loc.country ? ', ' + loc.country : '') + ' right now: ' + c.temperature_2m + '°C (feels ' + c.apparent_temperature + '°C), ' +
    (WMO[c.weather_code] || 'unknown conditions') + ', humidity ' + c.relative_humidity_2m + '%, wind ' + c.wind_speed_10m + ' km/h.';
  if (d.time) {
    for (let i = 0; i < Math.min(3, d.time.length); i++) {
      out += ' ' + d.time[i] + ': ' + d.temperature_2m_min[i] + '-' + d.temperature_2m_max[i] + '°C, ' + (WMO[d.weather_code[i]] || '') + ', rain chance ' + d.precipitation_probability_max[i] + '%.';
    }
  }
  return out;
}

async function webSearchData(env, q) {
  if (env.TAVILY_API_KEY) {
    try {
      const r = await fetch('https://api.tavily.com/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + env.TAVILY_API_KEY },
        body: JSON.stringify({ query: q, max_results: 5, include_answer: true }),
        signal: AbortSignal.timeout(6000),
      });
      const j = await r.json();
      let out = j.answer ? 'Summary: ' + j.answer + '\n' : '';
      for (const x of (j.results || []).slice(0, 5)) out += '- ' + x.title + ': ' + String(x.content || '').slice(0, 300) + ' (' + x.url + ')\n';
      if (out) return out;
    } catch (e) {}
  }
  try {
    const r = await fetch('https://html.duckduckgo.com/html/?q=' + encodeURIComponent(q), {
      headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36' },
      signal: AbortSignal.timeout(5000),
    });
    const h = await r.text();
    const out = [];
    const re = /class="result__a"[^>]*>(.*?)<\/a>[\s\S]*?class="result__snippet"[^>]*>(.*?)<\/a>/g;
    let m;
    const clean = (x) => x.replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/&#x27;/g, "'").replace(/&quot;/g, '"').trim();
    while ((m = re.exec(h)) && out.length < 5) out.push('- ' + clean(m[1]) + ': ' + clean(m[2]).slice(0, 300));
    if (out.length) return out.join('\n');
  } catch (e) {}
  return '';
}

// Returns extra system-prompt text with fresh facts, or '' when the question needs none.
async function liveContext(env, text) {
  try {
    if (!text || text.startsWith('[PHONE]') || text.length > 300) return '';
    const lower = text.toLowerCase();
    let data = '';
    if (/\b(weather|mausam|temperature|forecast|barish|baarish)\b/i.test(lower)) {
      const city = weatherCity(text);
      if (city) data = await weatherNow(city);
    }
    if (!data && /\b(news|khabar|khabrein|latest|breaking|score|price|rate|today|aaj ki|abhi|current|who is|what is the|kya hai|kaun hai|search|dhoond|dhundo|google)\b/i.test(lower) &&
        !/\b(open|call|whatsapp|flashlight|volume|alarm|timer|sms|message|youtube|play ?store|spotify|daraz|amazon|maps|facebook|instagram|tiktok|linkedin|pinterest|app)\b/i.test(lower)) {
      const q = text.replace(/\b(please|can you|could you|cortex|search for|search|dhoond(?:o)?|dhundo|google|karo|kro|batao|bata do)\b/gi, ' ').replace(/\s+/g, ' ').trim() || text;
      data = await webSearchData(env, q);
    }
    if (!data) return '';
    return '\n\nLIVE DATA (fetched just now from the internet):\n' + data +
      '\nANSWER the user directly from this LIVE DATA in 1-3 short sentences, in the user\'s language. Do NOT open Chrome or any app, and do NOT output a phone action for this question.';
  } catch (e) {
    return '';
  }
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: CORS_HEADERS });
    }
    if (request.method === 'GET' && env.DIAG === '1' && new URL(request.url).pathname === '/diag') {
      return json(await runDiag(env));
    }
    if (request.method !== 'POST') {
      return json({ error: 'method_not_allowed' }, 405);
    }

    let body;
    try {
      body = await request.json();
    } catch (e) {
      return json({ error: 'bad_request' }, 400);
    }

    // Admin-only: lets the admin panel show which provider keys are
    // configured, without ever exposing the key values themselves.
    if (body.action === 'status') {
      if (body.admin_secret !== ADMIN_SECRET) {
        return json({ error: 'unauthorized' }, 401);
      }
      return json({
        anthropic: !!env.ANTHROPIC_API_KEY,
        openai: !!env.OPENAI_API_KEY,
        gemini: !!env.GEMINI_API_KEY,
      });
    }

    // Admin-only: update the AI provider keys from the Admin Panel instead
    // of the Cloudflare dashboard. Needs a one-time setup of CF_API_TOKEN,
    // CF_ACCOUNT_ID and CF_SCRIPT_NAME (see README) so the worker can call
    // Cloudflare's own API to edit its own secrets. Any key left blank in
    // the request is left untouched, not cleared.
    if (body.action === 'set_keys') {
      if (body.admin_secret !== ADMIN_SECRET) {
        return json({ error: 'unauthorized' }, 401);
      }
      if (!env.CF_API_TOKEN || !env.CF_ACCOUNT_ID || !env.CF_SCRIPT_NAME) {
        return json({ error: 'cf_management_not_configured' }, 500);
      }
      const secrets = {};
      if (body.anthropic_key) secrets.ANTHROPIC_API_KEY = { type: 'secret_text', name: 'ANTHROPIC_API_KEY', text: body.anthropic_key };
      if (body.openai_key) secrets.OPENAI_API_KEY = { type: 'secret_text', name: 'OPENAI_API_KEY', text: body.openai_key };
      if (body.gemini_key) secrets.GEMINI_API_KEY = { type: 'secret_text', name: 'GEMINI_API_KEY', text: body.gemini_key };
      if (Object.keys(secrets).length === 0) {
        return json({ error: 'no_keys_provided' }, 400);
      }
      try {
        const cfRes = await fetch(
          `https://api.cloudflare.com/client/v4/accounts/${env.CF_ACCOUNT_ID}/workers/scripts/${env.CF_SCRIPT_NAME}/secrets-bulk`,
          {
            method: 'PATCH',
            headers: {
              'Content-Type': 'application/json',
              Authorization: `Bearer ${env.CF_API_TOKEN}`,
            },
            body: JSON.stringify({ secrets }),
          }
        );
        const cfData = await cfRes.json();
        if (!cfData.success) {
          return json({ error: (cfData.errors && cfData.errors[0] && cfData.errors[0].message) || 'cloudflare_update_failed' }, 500);
        }
        return json({ success: true, updated: Object.keys(secrets) });
      } catch (e) {
        return json({ error: 'cloudflare_request_failed' }, 500);
      }
    }

    // Create an image from a prompt (Gemini image model). Uses the same
    // GEMINI_API_KEY as chat. Needs a valid license, like every other request.
    // NOTE: Google may give free-tier keys zero image quota — that case is
    // reported back as "image_quota" so the app can explain it clearly.
    if (body.action === 'generate_image') {
      if (!body.license_key) return json({ error: 'license_required' }, 401);
      if (!body.prompt) return json({ error: 'bad_request' }, 400);
      if (!env.GEMINI_API_KEY) return json({ error: 'gemini_key_missing' }, 500);

      let imgLicense;
      try {
        imgLicense = await checkLicense(body.license_key);
      } catch (e) {
        return json({ error: 'license_check_failed' }, 500);
      }
      if (!imgLicense.valid) {
        return json({ error: imgLicense.reason || 'invalid_license' }, 403);
      }

      const imageModels = ['gemini-3.1-flash-image'];
      let lastMessage = '';
      for (const model of imageModels) {
        try {
          const res = await fetch(
            `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
            {
              method: 'POST',
              headers: { 'Content-Type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY },
              body: JSON.stringify({
                contents: [{ role: 'user', parts: [{ text: body.prompt }] }],
                generationConfig: { responseModalities: ['TEXT', 'IMAGE'] },
              }),
            }
          );
          const data = await res.json();
          if (data.error) {
            lastMessage = data.error.message || '';
            continue;
          }
          const parts = (data.candidates && data.candidates[0] && data.candidates[0].content && data.candidates[0].content.parts) || [];
          const imagePart = parts.find((p) => p.inlineData && p.inlineData.data);
          if (imagePart) {
            return json({
              image: imagePart.inlineData.data,
              mimeType: imagePart.inlineData.mimeType || 'image/png',
              text: parts.filter((p) => p.text).map((p) => p.text).join('\n'),
            });
          }
          lastMessage = 'no image in response';
        } catch (e) {
          lastMessage = String(e && e.message ? e.message : e);
        }
      }
      const quotaProblem = /quota|limit: 0|billing|exceeded|RESOURCE_EXHAUSTED|not available in your|permission/i.test(lastMessage);
      return json({ error: quotaProblem ? 'image_quota' : 'image_unavailable' }, 500);
    }

    // ---- Developer requests: only the owner's license may send them. They become a GitHub issue,
    // ---- which the "Cortex Developer Agent" workflow picks up and turns into a pull request.
    if (body.action === 'dev_request') {
      const lk = body.license_key;
      if (!lk || !env.OWNER_LICENSE || lk !== env.OWNER_LICENSE) return json({ error: 'not_owner' }, 403);
      let lc;
      try {
        lc = await checkLicense(lk);
      } catch (e) {
        return json({ error: 'license_check_failed' }, 500);
      }
      if (!lc.valid) return json({ error: 'invalid_license' }, 403);
      if (!env.GITHUB_TOKEN_DEV || !env.GITHUB_REPO) return json({ error: 'dev_not_configured' }, 500);
      const text = String(body.text || '').trim().slice(0, 4000);
      if (!text) return json({ error: 'bad_request' }, 400);
      const target = ['android', 'pc', 'both'].includes(body.target) ? body.target : 'android';
      const gh = await fetch('https://api.github.com/repos/' + env.GITHUB_REPO + '/issues', {
        method: 'POST',
        headers: {
          Authorization: 'Bearer ' + env.GITHUB_TOKEN_DEV,
          'User-Agent': 'cortex-worker',
          Accept: 'application/vnd.github+json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          title: 'Cortex request: ' + text.slice(0, 70),
          body: 'Target: ' + target + '\n\nRequest (spoken by the owner):\n\n' + text,
          labels: ['cortex-request'],
        }),
      });
      if (!gh.ok) return json({ error: 'github_' + gh.status }, 500);
      const issue = await gh.json();
      return json({ ok: true, url: issue.html_url });
    }

    const { provider, messages, license_key } = body;
    // CORTEX_RULES: optional text you set in Cloudflare (Settings -> Variables). It is added to every
    // request, so you can change Cortex's behaviour for ALL apps without rebuilding anything.
    let system = body.system;
    if (env.CORTEX_RULES) system = (system ? system + '\n\n' : '') + env.CORTEX_RULES;
    if (!license_key) return json({ error: 'license_required' }, 401);
    if (!provider || !messages) return json({ error: 'bad_request' }, 400);

    // Every message gets checked against the license backend before we spend
    // any AI-provider quota on it.
    let licenseCheck;
    try {
      licenseCheck = await checkLicense(license_key);
    } catch (e) {
      return json({ error: 'license_check_failed' }, 500);
    }
    if (!licenseCheck.valid) {
      return json({ error: licenseCheck.reason || 'invalid_license' }, 403);
    }

    try {
      if (provider === 'anthropic') {
        const anthropicBody = { model: 'claude-sonnet-5-5', max_tokens: 1800, messages };
        if (system) anthropicBody.system = system;
        const res = await fetch('https://api.anthropic.com/v1/messages', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'x-api-key': env.ANTHROPIC_API_KEY,
            'anthropic-version': '2023-06-01',
          },
          body: JSON.stringify(anthropicBody),
        });
        const data = await res.json();
        if (data.error) return json({ error: data.error.message || 'Anthropic error' }, 500);
        const text = (data.content || []).map((b) => b.text || '').join('\n');
        return json({ text });
      }

      if (provider === 'openai') {
        const chatMessages = system ? [{ role: 'system', content: system }, ...messages] : messages;
        const res = await fetch('https://api.openai.com/v1/chat/completions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${env.OPENAI_API_KEY}` },
          body: JSON.stringify({ model: 'gpt-4o-mini', messages: chatMessages, max_tokens: 1800 }),
        });
        const data = await res.json();
        if (data.error) return json({ error: data.error.message || 'OpenAI error' }, 500);
        return json({ text: (data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content) || '' });
      }

      if (provider === 'gemini') {
        const geminiBody = {
          contents: messages.map((m) => ({
            role: m.role === 'assistant' ? 'model' : 'user',
            parts: Array.isArray(m.content) ? m.content : [{ text: m.content }],
          })),
          generationConfig: { maxOutputTokens: 2000 },
        };
        if (!/LIVE DATA/.test(system || '')) system = (system || '') + await liveContext(env, lastUserText(messages));
        if (system) geminiBody.system_instruction = { parts: [{ text: system }] };

        // gemini-3.8-flash: current stable flagship Flash model (GA Sep 2026).
        // Google restricted the older 2.5 models to legacy users only in Sep
        // 2026 — a fallback chain that ends on 2.5 just surfaces that
        // deprecation error, so we call the one confirmed-current model directly.
        const callGemini = (body) => fetch(
          'https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent',
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY },
            body: JSON.stringify(body),
            signal: AbortSignal.timeout(9000),
          }
        );
        // Low "thinking" makes replies much faster; retry without it if the model rejects the setting.
        let data;
        try {
          let res = await callGemini({
            ...geminiBody,
            generationConfig: { ...geminiBody.generationConfig, thinkingConfig: { thinkingLevel: 'low' } },
          });
          data = await res.json();
          if (data.error && /think/i.test(data.error.message || '')) {
            res = await callGemini(geminiBody);
            data = await res.json();
          }
        } catch (e) {
          data = { error: { message: 'Gemini timeout: ' + String(e && e.message ? e.message : e) } };
        }
        if (data.error) {
          // Backup brains: when Gemini is out of quota we try other free services in turn.
          const fb = await fallbackChain(env, system, messages);
          if (fb !== null) return json({ text: fb });
          return json({ error: data.error.message || 'Gemini error' }, 500);
        }
        const parts = (data.candidates && data.candidates[0] && data.candidates[0].content && data.candidates[0].content.parts) || [];
        return json({ text: parts.map((p) => p.text || '').join('\n') });
      }

      return json({ error: 'unknown_provider' }, 400);
    } catch (e) {
      return json({ error: String(e && e.message ? e.message : e) }, 500);
    }
  },
};
