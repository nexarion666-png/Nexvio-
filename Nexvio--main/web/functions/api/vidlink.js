const ALLOWED_ORIGIN = "https://vidlink.pro";

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
    },
  });
}

export async function onRequest({ request }) {
  if (request.method === "OPTIONS") return json({ ok: true });
  if (request.method !== "GET") return json({ error: "Method not allowed" }, 405);

  const url = new URL(request.url);
  const tmdbId = url.searchParams.get("tmdbId") || "";
  const mediaType = url.searchParams.get("mediaType") || "";
  const season = Number(url.searchParams.get("season"));
  const episode = Number(url.searchParams.get("episode"));

  if (!/^\d{1,12}$/.test(tmdbId) || !["movie", "tv"].includes(mediaType)) {
    return json({ error: "A valid TMDB ID and mediaType (movie or tv) are required." }, 400);
  }
  if (mediaType === "tv" && (!Number.isInteger(season) || season < 1 || season > 100 || !Number.isInteger(episode) || episode < 1 || episode > 500)) {
    return json({ error: "A valid TV season and episode are required." }, 400);
  }

  const userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";
  const headers = {
    Accept: "application/json,*/*",
    "Accept-Language": "en-US,en;q=0.5",
    Origin: ALLOWED_ORIGIN,
    Referer: ALLOWED_ORIGIN + "/",
    "User-Agent": userAgent,
  };

  try {
    const encUrl = new URL("https://enc-dec.app/api/enc-vidlink");
    encUrl.searchParams.set("text", tmdbId);
    const encRes = await fetch(encUrl, {
      headers: { Accept: "application/json", "User-Agent": userAgent },
      signal: AbortSignal.timeout(10000),
    });
    if (!encRes.ok) return json({ error: "Vidlink ID service returned HTTP " + encRes.status }, 502);

    const encrypted = await encRes.json();
    if (typeof encrypted?.result !== "string" || !encrypted.result) {
      return json({ error: "Vidlink ID service returned an invalid response." }, 502);
    }

    const path = mediaType === "tv"
      ? "tv/" + encodeURIComponent(encrypted.result) + "/" + season + "/" + episode
      : "movie/" + encodeURIComponent(encrypted.result);
    const endpoint = new URL("https://vidlink.pro/api/b/" + path);
    const upstream = await fetch(endpoint, { headers, signal: AbortSignal.timeout(18000) });
    if (!upstream.ok) return json({ error: "Vidlink returned HTTP " + upstream.status }, 502);

    return json(await upstream.json());
  } catch (error) {
    const message = error instanceof Error ? error.message : "Upstream request failed";
    return json({ error: "Vidlink proxy failed: " + message }, 502);
  }
}
