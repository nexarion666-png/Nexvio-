/*
 * Vixsrc provider for Nexvio.
 * Adapted from the GPL-3.0 yoruix/nuvio-providers project.
 * Upstream: https://github.com/yoruix/nuvio-providers
 */
"use strict";

var BASE_URL = "https://vixsrc.to";
var USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";

function request(url, headers) {
  return fetch(url, {
    method: "GET",
    headers: Object.assign({
      "User-Agent": USER_AGENT,
      "Accept": "application/json, text/javascript, */*; q=0.01",
      "Accept-Language": "en-US,en;q=0.9",
      "Referer": BASE_URL + "/",
      "Origin": BASE_URL
    }, headers || {})
  }).then(function (response) {
    if (!response.ok) throw new Error("HTTP " + response.status + " from " + url);
    return response;
  });
}

function normalizePlaylistUrl(value) {
  // The site sometimes serializes URL separators as escaped slashes
  // (for example: https:\\/\\/vixsrc.to \\/playlist\\/769486).
  // Convert those back to URL separators before passing the URL to Android.
  var url = String(value || "").trim();
  url = url.replace(/\\\\\\//g, "/");
  url = url.replace(/\\\\/g, "/");
  url = url.replace(/\\s+/g, "");
  url = url.replace(/^(https?):\\/{3,}/i, "$1//");
  if (/^https?:\\/[^/]/i.test(url)) {
    url = url.replace(/^(https?):\\//i, "$1://");
  }
  return url;
}

function extractPlaylistData(html) {
  // Vixsrc now serves an API response containing an embed path, then the
  // embed page contains the signed playlist URL and short-lived token.
  var token = html.match(/["']?token["']?\s*:\s*["']([^"']+)["']/i);
  var expires = html.match(/["']?expires["']?\s*:\s*["']?(\d+)["']?/i);
  var playlist = html.match(/["']?url["']?\s*:\s*["']([^"']+)["']/i);
  if (!token || !expires || !playlist) return null;
  var expirySeconds = Number(expires[1]);
  if (isFinite(expirySeconds) && expirySeconds > 0 && expirySeconds * 1000 < Date.now()) {
    throw new Error("Vixsrc returned an expired stream token");
  }
  return { token: token[1], expires: expires[1], playlist: normalizePlaylistUrl(playlist[1]) };
}

function getStreams(tmdbId, mediaType, seasonNum, episodeNum, title, year) {
  mediaType = mediaType === "tv" ? "tv" : "movie";
  var pageUrl = mediaType === "tv"
    ? BASE_URL + "/tv/" + encodeURIComponent(tmdbId) + "/" + seasonNum + "/" + episodeNum
    : BASE_URL + "/movie/" + encodeURIComponent(tmdbId);
  var apiUrl = mediaType === "tv"
    ? BASE_URL + "/api/tv/" + encodeURIComponent(tmdbId) + "/" + seasonNum + "/" + episodeNum
    : BASE_URL + "/api/movie/" + encodeURIComponent(tmdbId);

  console.log("[Vixsrc] Trying canonical page -> masterPlaylist extraction first");
  var info = { title: title || "Vixsrc", year: year || "" };
  function fetchMasterFromPage(url, referer) {
      return request(url, {
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer": referer
      }).then(function (response) {
        return response.text();
      }).then(function (html) {
        var data = extractPlaylistData(html);
        if (data) return data;
        // Some Vixsrc pages expose the playlist as a direct HLS URL instead
        // of the token/url object.
        var direct = html.match(/https?:\/\/[^"'\s]+\.m3u8[^"'\s]*/i);
        if (direct) return { token: "", expires: "", playlist: normalizePlaylistUrl(direct[0]) };
        return null;
      });
    }

  return fetchMasterFromPage(pageUrl, BASE_URL + "/").then(function (data) {
      if (data) {
        console.log("[Vixsrc] Extracted stream data from canonical page");
        return { info: info, data: data };
      }
      console.log("[Vixsrc] Canonical page had no playlist; trying API embed fallback");
      return request(apiUrl, { "Referer": pageUrl }).then(function (response) {
        return response.json();
      }).then(function (apiData) {
        if (!apiData || !apiData.src) throw new Error("Vixsrc API response did not contain an embed path");
        var embedUrl = apiData.src.indexOf("http") === 0 ? apiData.src : BASE_URL + (apiData.src.charAt(0) === "/" ? "" : "/") + apiData.src;
        return fetchMasterFromPage(embedUrl, pageUrl).then(function (embedData) {
          if (!embedData) throw new Error("Vixsrc canonical and embed pages contained no playlist data");
          return { info: info, data: embedData };
        });
      });
    }).then(function (resolved) {
      var data = resolved.data;
      var masterUrl = data.playlist;
      if (data.token && data.expires) {
        var separator = masterUrl.indexOf("?") >= 0 ? "&" : "?";
        masterUrl += separator + "token=" + encodeURIComponent(data.token) +
          "&expires=" + encodeURIComponent(data.expires) + "&h=1&lang=en";
      }
      console.log("[Vixsrc] Fetching HLS master playlist");
      return request(masterUrl, {
        "Accept": "application/vnd.apple.mpegurl, application/x-mpegURL, */*",
        "Referer": pageUrl
      }).then(function (response) {
        return response.text();
      }).then(function (playlistText) {
        if (!playlistText || playlistText.indexOf("#EXTM3U") < 0) {
          throw new Error("Vixsrc response was not a valid HLS playlist");
        }
        var quality = "Auto";
        var resolution = playlistText.match(/RESOLUTION=\d+x(\d+)/g) || [];
        if (resolution.length) {
          var heights = resolution.map(function (entry) {
            return Number((entry.match(/x(\d+)/) || [])[1] || 0);
          });
          var best = Math.max.apply(Math, heights);
          if (best) quality = best + "p";
        }
        var subtitles = [];
        var subtitleRegex = /#EXT-X-MEDIA:[^\n]*TYPE=SUBTITLES[^\n]*URI="([^"]+)"[^\n]*/g;
        var match;
        while ((match = subtitleRegex.exec(playlistText)) !== null) {
          var label = (match[0].match(/NAME="([^"]+)"/) || [])[1] || "Subtitle";
          var subtitleUrl = match[1].indexOf("http") === 0 ? match[1] : masterUrl.substring(0, masterUrl.lastIndexOf("/") + 1) + match[1];
          subtitles.push({ url: subtitleUrl, language: label, name: label, headers: { "Referer": pageUrl, "User-Agent": USER_AGENT } });
        }
        return [{
          name: "Vixsrc",
          title: info.title + (info.year ? " (" + info.year + ")" : ""),
          url: masterUrl,
          quality: quality,
          type: "direct",
          headers: { "Referer": pageUrl, "Origin": BASE_URL, "User-Agent": USER_AGENT },
          subtitles: subtitles
        }];
      });
  }).catch(function (error) {
    console.error("[Vixsrc] Extraction failed: " + (error && error.message ? error.message : String(error)));
    throw error;
  });
}

module.exports = { getStreams: getStreams };
