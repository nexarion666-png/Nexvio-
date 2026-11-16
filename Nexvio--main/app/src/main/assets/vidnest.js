/*
 * Nexvio built-in Vidnest provider.
 * Independently implemented against the publicly exposed Vidnest API.
 *
 * Vidnest currently encrypts some server responses. Those responses are
 * decrypted by the upstream-compatible AES-GCM endpoint configured below.
 * The endpoint is a third-party service and may become unavailable.
 */
"use strict";

var VIDNEST_API = "https://first.vidnest.fun";
var VIDNEST_DECRYPT = "https://aesdec.nuvioapp.space/decrypt";
var VIDNEST_PROXY = "https://vidnest.animanga.fun/proxy";
var VIDNEST_PASSPHRASE = "A7kP9mQeXU2BWcD4fRZV+Sg8yN0/M5tLbC1HJQwYe6o=";
var VIDNEST_SERVERS = ["hollymoviehd", "primesrc", "ophim", "flixhq", "vidlink", "rogflix"];

var API_HEADERS = {
  "Accept": "*/*",
  "Accept-Language": "en-GB,en-US;q=0.9,en;q=0.8",
  "Origin": "https://vidnest.fun",
  "Referer": "https://vidnest.fun/",
  "User-Agent": "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/143.0.0.0 Mobile Safari/537.36"
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
      if (!response.ok) throw new Error("HTTP " + response.status + ": " + body.slice(0, 160));
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

function vidnestSources(data) {
  if (!data || typeof data !== "object") return [];
  if (Array.isArray(data.sources)) return data.sources;
  if (Array.isArray(data.streams)) return data.streams;
  if (typeof data.url === "string") return [{
    url: data.url, headers: data.headers, subtitles: data.subtitles
  }];
  if (typeof data.data === "string") return [{
    url: data.data, headers: data.headers
  }];
  return [];
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
  if (server === "flixhq") url += "?server=upcloud";

  return vidnestRequest(url, { method: "GET", headers: API_HEADERS })
    .then(function (body) {
      var payload = JSON.parse(body);
      return vidnestDecode(payload);
    })
    .then(function (data) {
      var sources = vidnestSources(data);
      var result = [];
      sources.forEach(function (source) {
        if (!source || typeof source !== "object") return;
        var rawUrl = source.file || source.url || source.src || source.link;
        var streamUrl = vidnestPlaybackUrl(rawUrl);
        if (!streamUrl) return;

        var label = source.label || source.source || "";
        var language = source.language ? " [" + source.language + "]" : "";
        var displayTitle = String(title || "Untitled");
        if (year) displayTitle += " (" + year + ")";
        if (mediaType === "tv") {
          displayTitle += " S" + String(season).padStart(2, "0") +
            "E" + String(episode).padStart(2, "0");
        }

        var headers = Object.assign({}, STREAM_HEADERS, source.headers || {});
        result.push({
          name: "Vidnest " + server.charAt(0).toUpperCase() + server.slice(1) +
            (label ? " - " + label : "") + language,
          title: displayTitle,
          url: streamUrl,
          quality: vidnestQuality(source.quality || source.label || rawUrl),
          headers: headers,
          provider: "vidnest"
        });
      });
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

  var tasks = VIDNEST_SERVERS.map(function (server) {
    return vidnestParseServer(
      server, type, id, title, year, season, episode
    );
  });

  return Promise.all(tasks).then(function (groups) {
    var streams = [];
    var seen = {};
    groups.forEach(function (group) {
      group.forEach(function (stream) {
        var key = stream.url;
        if (!seen[key]) {
          seen[key] = true;
          streams.push(stream);
        }
      });
    });
    console.log("[Vidnest] Found " + streams.length + " stream(s) across " +
      VIDNEST_SERVERS.length + " servers");
    return streams;
  });
}

module.exports = { getStreams: getStreams };
