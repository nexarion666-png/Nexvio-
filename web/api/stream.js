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

    res.statusCode = upstream.status;
    for (const name of ["accept-ranges", "content-length", "content-range", "content-type", "last-modified", "etag"]) {
      const value = upstream.headers.get(name);
      if (value) res.setHeader(name, value);
    }
    res.setHeader("X-Content-Type-Options", "nosniff");

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
