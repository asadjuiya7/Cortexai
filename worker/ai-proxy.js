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
    body: JSON.stringify({ model, messages: chat, max_tokens: 400 }),
    signal: AbortSignal.timeout(7000),
  });
  const j = await res.json();
  const t = j && j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content;
  if (!res.ok || !t) throw new Error('chat_failed ' + res.status + ' ' + JSON.stringify(j).slice(0, 120));
  return String(t);
}

// Backup models sometimes write {"open_app":"Chrome"} instead of
// {"device_action":"open_app","param":"Chrome"}. Fix that shape here.
const PHONE_ACTIONS = ['open_app','flashlight_on','flashlight_off','volume_up','volume_down','mute','unmute','call','send_sms','whatsapp_message','send_email','set_alarm','set_timer','web_search','youtube_search','navigate','open_url','battery_status','wifi_settings','bluetooth_settings','go_home','go_back','recents','notifications','lock_screen','screenshot','click_text','type_text','scroll_down','scroll_up','read_screen'];
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
  const chat = system ? [{ role: 'system', content: system }, ...plain] : plain;

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
      const out = await env.AI.run('@cf/meta/llama-3.3-70b-instruct-fp8-fast', { messages: chat, max_tokens: 400 });
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
  const r = await fallbackChainRaw(env, (system || '') + reminder, messages);
  return r === null ? null : fixActionShape(r);
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
        const anthropicBody = { model: 'claude-sonnet-5-5', max_tokens: 400, messages };
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
          body: JSON.stringify({ model: 'gpt-4o-mini', messages: chatMessages, max_tokens: 400 }),
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
          generationConfig: { maxOutputTokens: 400 },
        };
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
