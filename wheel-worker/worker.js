// Hearthigen punishment wheel: spin server (Cloudflare Worker)
//
// How punishments work: when someone earns one, they get the CURRENT punishment (a GitHub
// automation logs it and marks the current punishment "used"). The wheel then picks the NEXT one.
//
// The website calls POST /spin. This Worker:
//   1. reads data.json from GitHub,
//   2. checks the current punishment has been given out (otherwise the wheel is locked),
//   3. picks a random slice itself (so nobody can fake a result),
//   4. saves it as the new current punishment and takes that slice off the wheel.
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
    const url = new URL(request.url), path = url.pathname;
    // The site's test lab uses test-data.json so it never touches the real data
    const file = url.searchParams.get("file") === "test" ? "test-data.json" : "data.json";
    if (request.method === "GET" && path === "/") return reply({ok: true, service: "hearthigen-wheel"}, 200, cors);
    if (request.method !== "POST" || (path !== "/spin" && path !== "/test/reset")) return reply({error: "not_found"}, 404, cors);
    if (!ALLOWED_ORIGINS.includes(origin)) return reply({error: "forbidden"}, 403, cors);
    if (!env.GITHUB_TOKEN) return reply({error: "not_configured"}, 500, cors);
    if (path === "/test/reset") return resetTest(env, cors);

    for (let attempt = 0; attempt < 3; attempt++) {
      const got = await github(env, `repos/${REPO}/contents/${file}?ref=main`);
      if (!got.ok) return reply({error: "github_" + got.status}, 502, cors);
      const meta = await got.json();
      const data = JSON.parse(fromBase64(meta.content));

      const cur = data.currentPunishment || {};
      if (!cur.used) return reply({error: "locked"}, 409, cors);
      const wheel = Array.isArray(data.wheel) ? data.wheel : [];
      if (!wheel.length) return reply({error: "empty"}, 409, cors);

      const index = randomInt(wheel.length);
      const item = wheel[index];
      data.currentPunishment = {text: item.text};
      data.wheel = wheel.filter((_, i) => i !== index);

      const save = await github(env, `repos/${REPO}/contents/${file}`, {
        method: "PUT",
        body: JSON.stringify({
          message: (file === "data.json" ? "" : "Test lab: ") + `Wheel: next punishment is "${item.text}"`,
          content: toBase64(JSON.stringify(data, null, 2) + "\n"),
          sha: meta.sha,
          branch: "main",
        }),
      });
      if (save.ok) return reply({ok: true, index, text: item.text, wheel}, 200, cors);
      if (save.status !== 409 && save.status !== 422) return reply({error: "github_" + save.status}, 502, cors);
      // someone (or the mod) saved at the same moment: read again and re-check
    }
    return reply({error: "busy"}, 503, cors);
  },
};

// Test lab reset: copy the real data into test-data.json and add one test death so someone owes a punishment
async function resetTest(env, cors) {
  const real = await github(env, `repos/${REPO}/contents/data.json?ref=main`);
  if (!real.ok) return reply({error: "github_" + real.status}, 502, cors);
  const data = JSON.parse(fromBase64((await real.json()).content));
  const t = {};
  const players = (data.players || []).filter(p => !p.inactive);
  players.forEach(p => t[p.id] = Number(p.base) || 0);
  (data.deaths || []).filter(d => !d.reverted).forEach(d => { if (d.player in t) t[d.player] += deathPoints(d); });
  // whoever is closest to their next punishment gets a +5 test death
  const pick = players.map(p => ({p, away: PER_PUNISH - (t[p.id] % PER_PUNISH)})).sort((a, b) => a.away - b.away)[0];
  if (!pick) return reply({error: "no_players"}, 409, cors);
  data.deaths.push({id: "test" + Date.now(), player: pick.p.id, age: "end", totem: false, cause: "Test death", reason: "Made by the test lab", date: new Date().toISOString().slice(0, 10), time: new Date().toISOString(), source: "test"});
  let sha;
  const cur = await github(env, `repos/${REPO}/contents/test-data.json?ref=main`);
  if (cur.ok) sha = (await cur.json()).sha;
  const save = await github(env, `repos/${REPO}/contents/test-data.json`, {method: "PUT", body: JSON.stringify({message: "Test lab: reset test data", content: toBase64(JSON.stringify(data, null, 2) + "\n"), branch: "main", ...(sha ? {sha} : {})})});
  if (!save.ok) return reply({error: "github_" + save.status}, 502, cors);
  return reply({ok: true, owed: pick.p.nick || pick.p.name}, 200, cors);
}

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
