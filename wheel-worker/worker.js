// Hearthigen punishment wheel: spin server (Cloudflare Worker)
//
// The website calls POST /spin. This Worker:
//   1. reads data.json from GitHub,
//   2. checks someone has earned a punishment that isn't logged yet (otherwise the wheel is locked),
//   3. picks a random slice itself (so nobody can fake a result),
//   4. saves it: current punishment, punishment history, and takes the slice off the wheel.
// The GitHub token lives in the Worker's secret GITHUB_TOKEN and is never sent to visitors.

const REPO = "HardcoreTracker/hardcoretracker.github.io";
const ALLOWED_ORIGINS = ["https://hardcoretracker.github.io"];
const PER_PUNISH = 5;
const AGE_POINTS = {wood: 1, copper: 2, iron: 3, diamond: 4, end: 5};

export default {
  async fetch(request, env) {
    const origin = request.headers.get("Origin") || "";
    const cors = {
      "Access-Control-Allow-Origin": ALLOWED_ORIGINS.includes(origin) ? origin : ALLOWED_ORIGINS[0],
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Vary": "Origin",
    };
    if (request.method === "OPTIONS") return new Response(null, {status: 204, headers: cors});
    const path = new URL(request.url).pathname;
    if (request.method === "GET" && path === "/") return reply({ok: true, service: "hearthigen-wheel"}, 200, cors);
    if (request.method !== "POST" || path !== "/spin") return reply({error: "not_found"}, 404, cors);
    if (!ALLOWED_ORIGINS.includes(origin)) return reply({error: "forbidden"}, 403, cors);
    if (!env.GITHUB_TOKEN) return reply({error: "not_configured"}, 500, cors);

    for (let attempt = 0; attempt < 3; attempt++) {
      const file = await github(env, `repos/${REPO}/contents/data.json?ref=main`);
      if (!file.ok) return reply({error: "github_" + file.status}, 502, cors);
      const meta = await file.json();
      const data = JSON.parse(fromBase64(meta.content));

      const owed = owedPunishments(data);
      if (!owed.length) return reply({error: "locked"}, 409, cors);
      const wheel = Array.isArray(data.wheel) ? data.wheel : [];
      if (!wheel.length) return reply({error: "empty"}, 409, cors);

      const index = randomInt(wheel.length);
      const item = wheel[index];
      const who = owed[0];

      data.currentPunishment = {...(data.currentPunishment || {}), text: item.text};
      data.punishments = Array.isArray(data.punishments) ? data.punishments : [];
      const nextId = Math.max(0, ...data.punishments.map(p => parseInt(String(p.id).replace(/\D/g, ""), 10) || 0)) + 1;
      data.punishments.push({id: "p" + nextId, time: who.time, player: who.id, text: item.text, source: "wheel"});
      data.wheel = wheel.filter((_, i) => i !== index);

      const save = await github(env, `repos/${REPO}/contents/data.json`, {
        method: "PUT",
        body: JSON.stringify({
          message: `Wheel: ${who.name} gets "${item.text}"`,
          content: toBase64(JSON.stringify(data, null, 2) + "\n"),
          sha: meta.sha,
          branch: "main",
        }),
      });
      if (save.ok) return reply({ok: true, index, text: item.text, player: who.id, playerName: who.name, wheel}, 200, cors);
      if (save.status !== 409 && save.status !== 422) return reply({error: "github_" + save.status}, 502, cors);
      // someone (or the mod) saved at the same moment: read again and re-check
    }
    return reply({error: "busy"}, 503, cors);
  },
};

// Same rules as the website: base points + points for each death; every 5 points is a punishment.
function deathPoints(d) {
  if (typeof d.points === "number") return d.points;
  if (d.legacy) return 0;
  return (AGE_POINTS[d.age] || 0) + (d.totem ? 1 : 0);
}
const deathMs = d => d.time ? Date.parse(d.time) : (d.date ? Date.parse(d.date + "T12:00:00") : 0);

// Players who've earned more punishments than are logged, earliest first,
// with the time of the death that pushed them over (used as the punishment's time).
function owedPunishments(data) {
  const players = (data.players || []).filter(p => !p.inactive);
  const deaths = (data.deaths || []).filter(d => !d.reverted).sort((a, b) => deathMs(a) - deathMs(b));
  const given = {};
  (data.punishments || []).forEach(p => given[p.player] = (given[p.player] || 0) + 1);
  const out = [];
  for (const p of players) {
    const mine = deaths.filter(d => d.player === p.id);
    const total = (Number(p.base) || 0) + mine.reduce((s, d) => s + deathPoints(d), 0);
    const g = given[p.id] || 0;
    if (Math.floor(total / PER_PUNISH) <= g) continue;
    let running = Number(p.base) || 0, when = null;
    for (const d of mine) {
      running += deathPoints(d);
      if (running >= (g + 1) * PER_PUNISH) { when = d.time || null; break; }
    }
    out.push({id: p.id, name: p.nick || p.name, time: when || new Date().toISOString(), sortKey: when ? Date.parse(when) : Date.now()});
  }
  return out.sort((a, b) => a.sortKey - b.sortKey);
}

// Fair random number from 0 to n-1
function randomInt(n) {
  const limit = Math.floor(0x100000000 / n) * n, buf = new Uint32Array(1);
  do { crypto.getRandomValues(buf); } while (buf[0] >= limit);
  return buf[0] % n;
}

function github(env, path, opts = {}) {
  return fetch("https://api.github.com/" + path, {
    ...opts,
    headers: {
      "Authorization": "Bearer " + env.GITHUB_TOKEN,
      "Accept": "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "hearthigen-wheel",
      ...(opts.body ? {"Content-Type": "application/json"} : {}),
    },
  });
}
function fromBase64(b64) {
  const bin = atob(String(b64).replace(/\s/g, ""));
  return new TextDecoder().decode(Uint8Array.from(bin, c => c.charCodeAt(0)));
}
function toBase64(text) {
  const bytes = new TextEncoder().encode(text);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}
function reply(obj, status, cors) {
  return new Response(JSON.stringify(obj), {status, headers: {...cors, "Content-Type": "application/json", "Cache-Control": "no-store"}});
}
