/*
 * Nexvio built-in Vidnest provider.
 * Uses the current Vidnest API host and normalizes its server-specific
 * response formats into Nexvio stream entries.
 */
"use strict";

var VIDNEST_API = "https://new.vidnest.fun";
var VIDNEST_DECRYPT = "https://aesdec.nuvioapp.space/decrypt";
var VIDNEST_PROXY = "https://vidnest.animanga.fun/proxy";
var VIDNEST_PASSPHRASE = "A7kP9mQeXU2BWcD4fRZV+Sg8yN0/M5tLbC1HJQwYe6o=";
var VIDNEST_SERVERS = [
  "moviebox", "allmovies", "catflix", "hollymoviehd", "lamda", "flixhq"
];

var API_HEADERS = {
  "Accept": "*/*",
  "Accept-Encoding": "gzip, deflate, br, zstd",
  "Accept-Language": "en-GB,en-US;q=0.9,en;q=0.8",
  "Cache-Control": "no-cache",
  "Pragma": "no-cache",
  "Origin": "https://vidnest.fun",
  "Referer": "https://vidnest.fun/",
  "Sec-CH-UA": "\"Google Chrome\";v=\"143\", \"Chromium\";v=\"143\", \"Not A(Brand\";v=\"24\"",
  "Sec-CH-UA-Mobile": "?1",
  "Sec-CH-UA-Platform": "\"Android\"",
  "Sec-Fetch-Dest": "empty",
  "Sec-Fetch-Mode": "cors",
  "Sec-Fetch-Site": "same-site",
  "User-Agent": "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36"
};

var STREAM_HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/137.0.0.0 Safari/537.36",
  "Accept": "video/*;q=0.9,*/*;q=0.5",
  "Accept-Language": "en-US,en;q=0.9",
  "Accept-Encoding": "identity",
  "Referer": "https://vidnest.fun/",
  "Origin": "https://vidnest.fun"
};

function vidnestRequest(url, options) {
  return fetch(url, options || {}).then(function (response) {
    return response.text().then(function (body) {
      if (!response.ok) {
        var detail = body.replace(/\s+/g, " ").slice(0, 220);
        throw new Error("HTTP " + response.status + (detail ? ": " + detail : ""));
      }
      return body;
    });
  });
}

function vidnestDecode(payload) {
  if (!payload || !payload.encrypted || !payload.data) return Promise.resolve(payload);
  return vidnestRequest(VIDNEST_DECRYPT, {
    method: "POST",
    headers: { "Content-Type": "application/json", "Accept": "application/json" },
    body: JSON.stringify({
      encryptedData: payload.data,
      passphrase: VIDNEST_PASSPHRASE
    })
  }).then(function (body) {
    var decoded = JSON.parse(body);
    if (decoded.error) throw new Error("AES-GCM decryption failed: " + decoded.error);
    if (typeof decoded.decrypted !== "string") throw new Error("Decryption service returned no plaintext");
    return JSON.parse(decoded.decrypted);
  });
}

/* Vidnest servers do not share one response schema. Normalize the common
 * sources/streams/url[]/data.stream.playlist shapes without treating subtitle
 * captions as playable video links.
 */
function vidnestSources(data) {
  var found = [];
  var seen = {};
  function add(value, inherited) {
    if (!value) return;
    if (Array.isArray(value)) {
      value.forEach(function (item) { add(item, inherited); });
      return;
    }
    if (typeof value === "string") {
      if (/^https?:\/\//i.test(value)) {
        if (!seen[value]) {
          seen[value] = true;
          found.push(Object.assign({}, inherited || {}, { url: value }));
        }
      }
      return;
    }
    if (typeof value !== "object") return;

    var candidate = value.url || value.link || value.file || value.src ||
      value.streamUrl || value.playbackUrl || value.playlist;
    if (typeof candidate === "string" && /^https?:\/\//i.test(candidate)) {
      if (!seen[candidate]) {
        seen[candidate] = true;
        found.push(Object.assign({}, inherited || {}, value, { url: candidate }));
      }
    }
    if (Array.isArray(value.sources)) add(value.sources, value);
    if (Array.isArray(value.streams)) add(value.streams, value);
    if (Array.isArray(value.url)) add(value.url, value);
    if (value.data && typeof value.data === "object") add(value.data, value);
    if (value.stream && typeof value.stream === "object") add(value.stream, value);
  }
  add(data, {});
  return found;
}

function vidnestQuality(value) {
  var match = String(value || "").match(/(?:2160|1440|1080|720|576|540|480|360|240)p?/i);
  return match ? match[0].toUpperCase().replace(/P?$/, "P") : "Auto";
}

function vidnestPlaybackUrl(rawUrl) {
  var url = String(rawUrl || "").trim();
  if (!/^https?:\/\//i.test(url)) return "";
  if (
    url.indexOf("flashstream.cc") >= 0 ||
    url.indexOf("streamsvr/") >= 0 ||
    url.indexOf("/pl/") >= 0 ||
    url.indexOf("rogflix") >= 0 ||
    (url.indexOf("lethe399key.com") >= 0 && url.indexOf("/stream2/") >= 0)
  ) {
    var origin = url.indexOf("lethe399key.com") >= 0
      ? "https://lethe399key.com"
      : "https://flashstream.cc";
    var proxyHeaders = {
      "User-Agent": STREAM_HEADERS["User-Agent"],
      "Accept": "*/*",
      "Origin": origin,
      "Referer": origin + "/"
    };
    return VIDNEST_PROXY + "?url=" + encodeURIComponent(url) +
      "&headers=" + encodeURIComponent(JSON.stringify(proxyHeaders));
  }
  return url;
}

function vidnestParseServer(server, mediaType, tmdbId, title, year, season, episode) {
  var url = VIDNEST_API + "/" + server + "/" + mediaType + "/" + encodeURIComponent(tmdbId);
  if (mediaType === "tv") url += "/" + season + "/" + episode;
  if (server === "flixhq" || server === "onehd") url += "?server=upcloud";

  return vidnestRequest(url, { method: "GET", headers: API_HEADERS })
    .then(function (body) {
      return vidnestDecode(JSON.parse(body));
    })
    .then(function (data) {
      var sources = vidnestSources(data);
      var result = [];
      sources.forEach(function (source) {
        var rawUrl = source.url || source.link || source.file || source.src ||
          source.streamUrl || source.playbackUrl || source.playlist;
        var streamUrl = vidnestPlaybackUrl(rawUrl);
        if (!streamUrl) return;

        var label = source.label || source.source || source.name || source.resolution || "";
        var language = source.language || source.lang || "";
        var displayTitle = String(title || "Untitled");
        if (year) displayTitle += " (" + year + ")";
        if (mediaType === "tv") {
          displayTitle += " S" + String(season).padStart(2, "0") +
            "E" + String(episode).padStart(2, "0");
        }
        var headers = Object.assign({}, STREAM_HEADERS, source.headers || {});
        result.push({
          name: "Vidnest " + server.charAt(0).toUpperCase() + server.slice(1) +
            (label ? " - " + label : "") + (language ? " [" + language + "]" : ""),
          title: displayTitle,
          url: streamUrl,
          quality: vidnestQuality(source.quality || source.resolution || label || rawUrl),
          headers: headers,
          provider: "vidnest"
        });
      });
      console.log("[Vidnest] " + server + " returned " + result.length + " stream(s)");
      return result;
    }).catch(function (error) {
      console.log("[Vidnest] " + server + " unavailable: " + String(error && error.message || error));
      return [];
    });
}

function getStreams(tmdbId, mediaType, season, episode, title, year) {
  var type = mediaType === "tv" ? "tv" : "movie";
  var id = String(tmdbId || "").trim();
  if (!/^\d+$/.test(id)) return Promise.resolve([]);

  // Keep requests sequential: Nexvio enforces a six-request in-flight
  // ceiling per provider, and other providers may be using the same engine.
  // Sequential requests avoid Vidnest exhausting its own request budget.
  var streams = [];
  var seen = {};
  return VIDNEST_SERVERS.reduce(function (chain, server) {
    return chain.then(function () {
      return vidnestParseServer(server, type, id, title, year, season, episode);
    }).then(function (group) {
      group.forEach(function (stream) {
        if (!seen[stream.url]) {
          seen[stream.url] = true;
          streams.push(stream);
        }
      });
    });
  }, Promise.resolve()).then(function () {
    console.log("[Vidnest] Found " + streams.length + " stream(s) across " +
      VIDNEST_SERVERS.length + " servers");
    return streams;
  });
}

module.exports = { getStreams: getStreams };
