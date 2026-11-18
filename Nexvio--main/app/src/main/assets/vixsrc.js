/*
 * Vixsrc provider for Nexvio.
 * Adapted from the GPL-3.0 yoruix/nuvio-providers project.
 * Upstream: https://github.com/yoruix/nuvio-providers
 */
"use strict";

var TMDB_API_KEY = "68e094699525b18a70bab2f86b1fa706";
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

function getTmdbInfo(tmdbId, mediaType) {
  var endpoint = mediaType === "tv" ? "tv" : "movie";
  return request("https://api.themoviedb.org/3/" + endpoint + "/" + encodeURIComponent(tmdbId) + "?api_key=" + TMDB_API_KEY, {
    "Accept": "application/json,*/*",
    "Referer": BASE_URL + "/"
  }).then(function (response) {
    return response.json();
  }).then(function (data) {
    var title = mediaType === "tv" ? data.name : data.title;
    var date = mediaType === "tv" ? data.first_air_date : data.release_date;
    return { title: title || "Vixsrc", year: date ? String(date).substring(0, 4) : "" };
  });
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
  return { token: token[1], expires: expires[1], playlist: playlist[1] };
}

function getStreams(tmdbId, mediaType, seasonNum, episodeNum) {
  mediaType = mediaType === "tv" ? "tv" : "movie";
  var pageUrl = mediaType === "tv"
    ? BASE_URL + "/tv/" + encodeURIComponent(tmdbId) + "/" + seasonNum + "/" + episodeNum
    : BASE_URL + "/movie/" + encodeURIComponent(tmdbId);
  var apiUrl = mediaType === "tv"
    ? BASE_URL + "/api/tv/" + encodeURIComponent(tmdbId) + "/" + seasonNum + "/" + episodeNum
    : BASE_URL + "/api/movie/" + encodeURIComponent(tmdbId);

  console.log("[Vixsrc] Trying canonical page -> masterPlaylist extraction first");
  return getTmdbInfo(tmdbId, mediaType).then(function (info) {
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
        var direct = html.match(/https?:\\/\\/[^"'\\s]+\\.m3u8[^"'\\s]*/i);
        if (direct) return { token: "", expires: "", playlist: direct[0] };
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
        var resolution = playlistText.match(/RESOLUTION=\\d+x(\\d+)/g) || [];
        if (resolution.length) {
          var heights = resolution.map(function (entry) {
            return Number((entry.match(/x(\\d+)/) || [])[1] || 0);
          });
          var best = Math.max.apply(Math, heights);
          if (best) quality = best + "p";
        }
        var subtitles = [];
        var subtitleRegex = /#EXT-X-MEDIA:[^\\n]*TYPE=SUBTITLES[^\\n]*URI="([^"]+)"[^\\n]*/g;
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
    });
  });
  }).catch(function (error) {
    console.error("[Vixsrc] Extraction failed: " + (error && error.message ? error.message : String(error)));
    throw error;
  });
}

module.exports = { getStreams: getStreams };
