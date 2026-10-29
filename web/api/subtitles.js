const API_KEY = process.env.OPENSUBTITLES_API_KEY || "";
const API = "https://api.opensubtitles.com/api/v1";
const json = (res, status, body) => { res.statusCode = status; res.setHeader("Content-Type", "application/json; charset=utf-8"); res.setHeader("Cache-Control", "no-store"); return res.end(JSON.stringify(body)); };
const clean = (v, n = 160) => String(v || "").trim().slice(0, n);
const lang = v => /^[a-z]{2,3}$/i.test(v) ? v.toLowerCase() : "en";
const toVtt = input => { let s = String(input || "").replace(/^\uFEFF/, "").replace(/\r/g, "").trim(); if (/^WEBVTT(?:\s|$)/i.test(s)) return s + "\n"; s = s.replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, "$1.$2"); return "WEBVTT\n\n" + s + "\n"; };
export default async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  if (req.method === "OPTIONS") { res.statusCode = 204; return res.end(); }
  if (req.method !== "GET") return json(res, 405, { error: "Method not allowed" });
  if (!API_KEY) return json(res, 503, { error: "Subtitles need configuration. Add OPENSUBTITLES_API_KEY to the Nexvio website's Vercel environment variables." });
  const q = req.query || {}, action = clean(q.action, 20);
  const headers = { "Api-Key": API_KEY, "User-Agent": "Nexvio Web v1.0", "Accept": "application/json" };
  try {
    if (action === "search") {
      const title = clean(q.title), type = clean(q.type, 8), year = clean(q.year, 4), language = lang(clean(q.language, 3));
      if (!title || !["movie", "tv"].includes(type)) return json(res, 400, { error: "A title and media type are required." });
      const params = new URLSearchParams({ query: title, languages: language, type });
      if (/^\d{4}$/.test(year)) params.set("year", year);
      if (type === "tv") {
        const season = Number(q.season), episode = Number(q.episode);
        if (!Number.isInteger(season) || season < 1 || season > 99 || !Number.isInteger(episode) || episode < 1 || episode > 999) return json(res, 400, { error: "A valid season and episode are required." });
        params.set("season_number", String(season)); params.set("episode_number", String(episode));
      }
      const response = await fetch(API + "/subtitles?" + params, { headers, signal: AbortSignal.timeout(15000) });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) return json(res, response.status === 429 ? 429 : 502, { error: data?.message || "Subtitle provider search failed." });
      const subtitles = (Array.isArray(data?.data) ? data.data : []).flatMap(entry => {
        const a = entry?.attributes || {}, files = Array.isArray(a.files) ? a.files : [];
        return files.filter(f => Number.isFinite(Number(f.file_id))).map(f => ({ id: String(f.file_id), fileId: Number(f.file_id), language: lang(a.language || language), label: clean(a.language || language, 24).toUpperCase() + " · " + clean(a.release || a.feature_details?.title || title, 120), release: clean(a.release, 100), downloads: Number(a.download_count || 0) }));
      }).slice(0, 15);
      return json(res, 200, { subtitles, message: subtitles.length ? "" : "No matching subtitles found for this title, language, season, and episode." });
    }
    if (action === "file") {
      const fileId = Number(q.fileId);
      if (!Number.isSafeInteger(fileId) || fileId < 1) return json(res, 400, { error: "A valid subtitle file ID is required." });
      const response = await fetch(API + "/download", { method: "POST", headers: { ...headers, "Content-Type": "application/json" }, body: JSON.stringify({ file_id: fileId, sub_format: "srt" }), signal: AbortSignal.timeout(15000) });
      const data = await response.json().catch(() => ({}));
      if (!response.ok || typeof data?.link !== "string") return json(res, 502, { error: data?.message || "Subtitle provider could not prepare this file." });
      let url; try { url = new URL(data.link); } catch { return json(res, 502, { error: "Subtitle provider returned an invalid download link." }); }
      if (url.protocol !== "https:" || !(url.hostname === "opensubtitles.com" || url.hostname.endsWith(".opensubtitles.com"))) return json(res, 502, { error: "Subtitle provider returned an untrusted download host." });
      const file = await fetch(url, { headers: { "User-Agent": "Nexvio Web v1.0" }, signal: AbortSignal.timeout(20000) });
      if (!file.ok) return json(res, 502, { error: "Could not retrieve the subtitle file." });
      const raw = await file.text();
      if (raw.length > 2000000) return json(res, 413, { error: "Subtitle file is too large." });
      res.statusCode = 200; res.setHeader("Content-Type", "text/vtt; charset=utf-8"); res.setHeader("Cache-Control", "no-store");
      return res.end(toVtt(raw));
    }
    return json(res, 400, { error: "Unknown subtitle action." });
  } catch (error) { return json(res, 502, { error: error instanceof Error ? error.message : "Subtitle service request failed." }); }
}
