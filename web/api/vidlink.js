const ALLOWED_ORIGIN = "https://vidlink.pro";
const json = (res, status, body) => {
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  res.end(JSON.stringify(body));
};

export default async function handler(req, res) {
  if (req.method === "OPTIONS") return json(res, 200, { ok: true });
  if (req.method !== "GET") return json(res, 405, { error: "Method not allowed" });

  const q = req.query || {};
  const tmdbId = String(q.tmdbId || "");
  const mediaType = String(q.mediaType || "");
  const season = Number(q.season);
  const episode = Number(q.episode);
  if (!/^\d{1,12}$/.test(tmdbId) || !["movie", "tv"].includes(mediaType)) {
    return json(res, 400, { error: "A valid TMDB ID and mediaType (movie or tv) are required." });
  }
  if (mediaType === "tv" && (!Number.isInteger(season) || season < 1 || season > 100 || !Number.isInteger(episode) || episode < 1 || episode > 500)) {
    return json(res, 400, { error: "A valid TV season and episode are required." });
  }

  const headers = {
    Accept: "application/json,*/*",
    "Accept-Language": "en-US,en;q=0.5",
    Origin: ALLOWED_ORIGIN,
    Referer: ALLOWED_ORIGIN + "/",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
  };
  try {
    const encUrl = new URL("https://enc-dec.app/api/enc-vidlink");
    encUrl.searchParams.set("text", tmdbId);
    const encRes = await fetch(encUrl, { headers: { Accept: "application/json", "User-Agent": headers["User-Agent"] }, signal: AbortSignal.timeout(10000) });
    if (!encRes.ok) return json(res, 502, { error: "Vidlink ID service returned HTTP " + encRes.status });
    const encrypted = await encRes.json();
    if (typeof encrypted?.result !== "string" || !encrypted.result) return json(res, 502, { error: "Vidlink ID service returned an invalid response." });

    const endpoint = new URL("https://vidlink.pro/api/b/" + (mediaType === "tv"
      ? "tv/" + encodeURIComponent(encrypted.result) + "/" + season + "/" + episode
      : "movie/" + encodeURIComponent(encrypted.result)));
    const upstream = await fetch(endpoint, { headers, signal: AbortSignal.timeout(18000) });
    if (!upstream.ok) return json(res, 502, { error: "Vidlink returned HTTP " + upstream.status });
    const data = await upstream.json();
    return json(res, 200, data);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Upstream request failed";
    return json(res, 502, { error: "Vidlink proxy failed: " + message });
  }
}
