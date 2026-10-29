import { Readable } from "node:stream";

const allowedHost = (hostname) =>
  hostname === "hakunaymatata.com" || hostname.endsWith(".hakunaymatata.com");

export default async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Range, Content-Type");
  res.setHeader("Cache-Control", "no-store");

  if (req.method === "OPTIONS") {
    res.statusCode = 204;
    return res.end();
  }
  if (req.method !== "GET" && req.method !== "HEAD") {
    res.statusCode = 405;
    res.setHeader("Allow", "GET, HEAD, OPTIONS");
    return res.end("Method not allowed");
  }

  const rawUrl = typeof req.query?.url === "string" ? req.query.url : "";
  const download = req.query?.download === "1";
  if (download && /\.m3u8(?:$|[?#])/i.test(rawUrl)) {
    res.statusCode = 415;
    res.setHeader("Content-Type", "text/plain; charset=utf-8");
    return res.end("This source is an HLS playlist, not a complete video file. Downloading the playlist alone will not save the movie.");
  }
  let target;
  try {
    target = new URL(rawUrl);
  } catch {
    res.statusCode = 400;
    return res.end("A valid media URL is required");
  }
  if (target.protocol !== "https:" || !allowedHost(target.hostname.toLowerCase())) {
    res.statusCode = 403;
    return res.end("This media host is not allowed");
  }

  const headers = { "User-Agent": "Nexvio/1.0" };
  if (req.headers.range) headers.Range = req.headers.range;

  try {
    const upstream = await fetch(target, {
      method: req.method,
      headers,
      redirect: "follow",
      signal: AbortSignal.timeout(30000),
    });

    const contentType = (upstream.headers.get("content-type") || "").toLowerCase();
    if (download && (contentType.includes("mpegurl") || contentType.includes("x-mpegurl") || contentType.includes("application/vnd.apple"))) {
      res.statusCode = 415;
      res.setHeader("Content-Type", "text/plain; charset=utf-8");
      return res.end("Vidlink returned a streaming playlist rather than a single video file. This website cannot save the full movie from this source as one file yet.");
    }
    if (download && !contentType.startsWith("video/") && !contentType.includes("application/octet-stream")) {
      res.statusCode = 415;
      res.setHeader("Content-Type", "text/plain; charset=utf-8");
      return res.end("This source did not return a downloadable video file. Try streaming it instead.");
    }

    res.statusCode = upstream.status;
    for (const name of ["accept-ranges", "content-length", "content-range", "content-type", "last-modified", "etag"]) {
      const value = upstream.headers.get(name);
      if (value) res.setHeader(name, value);
    }
    res.setHeader("X-Content-Type-Options", "nosniff");
    if (download) {
      const extension = contentType.includes("mp4") ? "mp4" : contentType.includes("webm") ? "webm" : contentType.includes("matroska") ? "mkv" : "video";
      const requestedName = typeof req.query?.name === "string" ? req.query.name : "Nexvio video";
      const safeName = requestedName.normalize("NFKC").replace(/[^a-zA-Z0-9 _.-]/g, "").replace(/\s+/g, " ").trim().replace(/[. ]+$/g, "").slice(0, 120) || "Nexvio video";
      const filename = safeName + "." + extension;
      res.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
    }

    if (req.method === "HEAD" || !upstream.body) return res.end();
    Readable.fromWeb(upstream.body).on("error", () => {
      if (!res.headersSent) res.statusCode = 502;
      res.destroy();
    }).pipe(res);
  } catch (error) {
    if (!res.headersSent) {
      res.statusCode = 502;
      res.setHeader("Content-Type", "text/plain; charset=utf-8");
    }
    res.end("Media proxy failed: " + (error instanceof Error ? error.message : "upstream request failed"));
  }
}
