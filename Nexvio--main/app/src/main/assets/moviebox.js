/*
 * Nexvio MovieBox built-in scraper.
 * Independently implemented for Nexvio; MovieBox API protocol compatibility.
 * Added: 2026-11-14. Supports movie and TV episode stream discovery.
 */
"use strict";

var CryptoJS = require("crypto-js");
var API_BASE = "https://api3.aoneroom.com";
var KEY_B64_DEFAULT = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==";
var PACKAGE_INFO = {
  package_name: "com.community.mbox.in",
  version_name: "3.0.03.0529.03",
  version_code: 50020042
};
var SECRET_KEY = CryptoJS.enc.Base64.parse(
  CryptoJS.enc.Base64.parse(KEY_B64_DEFAULT).toString(CryptoJS.enc.Utf8)
);
var deviceId = "";
var selectedBrand = "";
var selectedModel = "";

function initializeSession() {
  if (deviceId) return;
  var chars = "0123456789abcdef";
  for (var i = 0; i < 32; i++) deviceId += chars.charAt(Math.floor(Math.random() * 16));
  var brandModels = {
    Samsung: ["SM-S918B", "SM-A528B", "SM-M336B"],
    Xiaomi: ["2201117TI", "M2012K11AI", "Redmi Note 11"],
    OnePlus: ["LE2111", "CPH2449", "IN2023"],
    Google: ["Pixel 6", "Pixel 7", "Pixel 8"],
    Realme: ["RMX3085", "RMX3360", "RMX3551"]
  };
  var brands = Object.keys(brandModels);
  selectedBrand = brands[Math.floor(Math.random() * brands.length)].toLowerCase();
  var models = brandModels[selectedBrand.charAt(0).toUpperCase() + selectedBrand.slice(1)];
  selectedModel = models[Math.floor(Math.random() * models.length)];
}

function md5(value) {
  return CryptoJS.MD5(value).toString(CryptoJS.enc.Hex);
}

function decodeQueryPart(value) {
  try { return decodeURIComponent(String(value).replace(/\+/g, " ")); }
  catch (_) { return String(value); }
}

function canonicalUrl(value) {
  // Avoid URL.searchParams: Nexvio's lightweight WebView URL shim does not
  // implement it consistently across Android System WebView versions.
  var raw = String(value || "");
  var withoutFragment = raw.split("#")[0];
  var queryAt = withoutFragment.indexOf("?");
  var address = queryAt >= 0 ? withoutFragment.slice(0, queryAt) : withoutFragment;
  var queryString = queryAt >= 0 ? withoutFragment.slice(queryAt + 1) : "";
  var path = address.replace(/^https?:\\/\\/[^/]+/i, "");
  if (!path) path = "/";
  if (!queryString) return path;

  var entries = queryString.split("&").filter(function (part) { return part.length > 0; }).map(function (part, index) {
    var equalsAt = part.indexOf("=");
    var rawKey = equalsAt >= 0 ? part.slice(0, equalsAt) : part;
    var rawValue = equalsAt >= 0 ? part.slice(equalsAt + 1) : "";
    var key = decodeQueryPart(rawKey);
    return { key: key, value: decodeQueryPart(rawValue), index: index };
  });
  entries.sort(function (a, b) {
    if (a.key < b.key) return -1;
    if (a.key > b.key) return 1;
    return a.index - b.index;
  });
  var canonicalQuery = [];
  var lastKey = null;
  var values = [];
  entries.forEach(function (entry) {
    if (lastKey !== null && entry.key !== lastKey) {
      canonicalQuery.push(values.join("&"));
      values = [];
    }
    lastKey = entry.key;
    values.push(entry.key + "=" + entry.value);
  });
  if (values.length) canonicalQuery.push(values.join("&"));
  return path + "?" + canonicalQuery.join("&");
}

function signature(method, accept, contentType, url, body, timestamp) {
  var bodyHash = "";
  var bodyLength = "";
  if (body) {
    var bodyWords = CryptoJS.enc.Utf8.parse(body);
    bodyHash = md5(bodyWords);
    bodyLength = bodyWords.sigBytes.toString();
  }
  var canonical = method.toUpperCase() + "\n" + (accept || "") + "\n" +
    (contentType || "") + "\n" + bodyLength + "\n" + timestamp + "\n" +
    bodyHash + "\n" + canonicalUrl(url);
  return timestamp + "|2|" +
    CryptoJS.HmacMD5(canonical, SECRET_KEY).toString(CryptoJS.enc.Base64);
}

function request(method, url, body, customHeaders) {
  return new Promise(function (resolve, reject) {
    initializeSession();
    var timestamp = Date.now();
    var accept = (customHeaders && customHeaders.Accept) || "application/json";
    var contentType = (customHeaders && customHeaders["Content-Type"]) ||
      (body ? "application/json; charset=utf-8" : "application/json");
    var clientInfo = {
      package_name: PACKAGE_INFO.package_name,
      version_name: PACKAGE_INFO.version_name,
      version_code: PACKAGE_INFO.version_code,
      os: "android",
      os_version: "16",
      device_id: deviceId,
      install_store: "ps",
      gaid: "d7578036d13336cc",
      brand: selectedBrand,
      model: selectedModel,
      system_language: "en",
      net: "NETWORK_WIFI",
      region: "IN",
      timezone: "Asia/Calcutta",
      sp_code: ""
    };
    var headers = {
      "Accept": accept,
      "Content-Type": contentType,
      "x-client-token": timestamp + "," + md5(String(timestamp).split("").reverse().join("")),
      "x-tr-signature": signature(method, accept, contentType, url, body, timestamp),
      "User-Agent": PACKAGE_INFO.package_name + "/" + PACKAGE_INFO.version_code +
        " (Linux; U; Android 16; en_IN; " + selectedModel + "; Build/BP22.250325.006; Cronet/133.0.6876.3)",
      "x-client-info": JSON.stringify(clientInfo),
      "x-client-status": "0"
    };
    Object.keys(customHeaders || {}).forEach(function (key) { headers[key] = customHeaders[key]; });
    var options = { method: method, headers: headers };
    if (body) options.body = body;
    var attempts = 0;
    function run() {
      attempts++;
      fetch(url, options).then(function (response) {
        if (!response.ok) {
          response.text().then(function (bodyText) {
            if ((response.status === 403 || response.status === 429 || response.status >= 500) && attempts < 3) {
              setTimeout(run, attempts * 700);
              return;
            }
            var detail = String(bodyText || "").replace(/\\s+/g, " ").slice(0, 240);
            reject(new Error("MovieBox API returned HTTP " + response.status +
              (detail ? " — " + detail : "")));
          }).catch(function () {
            reject(new Error("MovieBox API returned HTTP " + response.status));
          });
          return;
        }
        Promise.all([response.text(), Promise.resolve(response.headers)]).then(function (values) {
          var data;
          try { data = JSON.parse(values[0]); } catch (_) { data = values[0]; }
          resolve({ data: data, headers: values[1] });
        });
      }).catch(function (error) {
        if (attempts < 3) setTimeout(run, attempts * 700);
        else reject(error);
      });
    }
    run();
  });
}

function normalizeTitle(value) {
  return String(value || "").toLowerCase()
    .replace(/\[[^\]]*\]/g, " ")
    .replace(/\([^)]*\)/g, " ")
    .replace(/\b(dub|dubbed|hd|4k|hindi|tamil|telugu|dual audio)\b/gi, " ")
    .replace(/:/g, " ").replace(/[^\w\s]/g, " ").replace(/\s+/g, " ").trim();
}

function searchSubjects(title) {
  var url = API_BASE + "/wefeed-mobile-bff/subject-api/search/v2";
  var body = JSON.stringify({ page: 1, perPage: 20, keyword: title });
  return request("POST", url, body, {}).then(function (response) {
    var groups = response.data && response.data.data && response.data.data.results;
    if (!Array.isArray(groups)) return [];
    var subjects = [];
    groups.forEach(function (group) {
      if (Array.isArray(group.subjects)) subjects = subjects.concat(group.subjects);
    });
    return subjects;
  });
}

function chooseSubject(subjects, title, year, mediaType) {
  var wanted = normalizeTitle(title);
  var wantedType = mediaType === "movie" ? 1 : 2;
  var best = null;
  var bestScore = 0;
  subjects.forEach(function (subject) {
    if (Number(subject.subjectType) !== wantedType) return;
    var candidate = normalizeTitle(subject.title);
    if (!candidate || !wanted) return;
    var candidateYear = String(subject.year || subject.releaseDate || "").slice(0, 4);
    var score = candidate === wanted ? 50 :
      (candidate.indexOf(wanted) >= 0 || wanted.indexOf(candidate) >= 0 ? 15 : 0);
    if (year && candidateYear && String(year) === candidateYear) score += 35;
    if (score > bestScore) { bestScore = score; best = subject; }
  });
  return bestScore >= 40 ? best : null;
}

function qualityLabel(stream) {
  var value = String(stream.resolutions || stream.quality || "");
  var match = value.match(/(\d{3,4})/);
  return match ? match[1] + "p" : "Auto";
}

function getCaptions(subjectId, streamId, authHeaders, language) {
  var urls = [
    API_BASE + "/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=" +
      encodeURIComponent(subjectId) + "&streamId=" + encodeURIComponent(streamId),
    API_BASE + "/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=" +
      encodeURIComponent(subjectId) + "&resourceId=" + encodeURIComponent(streamId) + "&episode=0"
  ];
  return Promise.all(urls.map(function (url) {
    return request("GET", url, null, authHeaders).then(function (response) {
      var captions = response.data && response.data.data && response.data.data.extCaptions;
      return Array.isArray(captions) ? captions.filter(function (caption) { return !!caption.url; }).map(function (caption) {
        return {
          url: caption.url,
          language: caption.language || caption.lanName || caption.lan || "en",
          name: (caption.lanName || caption.language || "Subtitle") + " (" + language + ")",
          headers: { Referer: API_BASE }
        };
      }) : [];
    }).catch(function () { return []; });
  })).then(function (parts) { return parts[0].concat(parts[1]); });
}

function getStreams(tmdbId, mediaType, seasonNum, episodeNum, mediaTitle, mediaYear) {
  var type = mediaType === "movie" ? "movie" : "tv";
  var title = String(mediaTitle || "").trim();
  var year = String(mediaYear || "").trim();
  if (!title) return Promise.reject(new Error("MovieBox needs a movie or episode opened from Nexvio Movies/Series."));
  var season = type === "tv" ? Math.max(1, Number(seasonNum) || 1) : 0;
  var episode = type === "tv" ? Math.max(1, Number(episodeNum) || 1) : 0;

  return searchSubjects(title).then(function (subjects) {
    var match = chooseSubject(subjects, title, year, type);
    if (!match) throw new Error("MovieBox could not find a matching title for " + title + ".");
    var detailUrl = API_BASE + "/wefeed-mobile-bff/subject-api/get?subjectId=" + encodeURIComponent(match.subjectId);
    return request("GET", detailUrl, null, {}).then(function (detailResponse) {
      var data = detailResponse.data && detailResponse.data.data;
      if (!data) throw new Error("MovieBox returned no title details.");
      var authHeaders = {};
      var xUser = detailResponse.headers && detailResponse.headers.get("x-user");
      if (xUser) {
        try {
          var token = JSON.parse(xUser).token;
          if (token) authHeaders.Authorization = "Bearer " + token;
        } catch (_) {}
      }
      var languages = [];
      if (Array.isArray(data.dubs)) {
        data.dubs.forEach(function (dub) {
          if (String(dub.subjectId) === String(match.subjectId)) {
            languages.unshift({ id: dub.subjectId, language: dub.lanName || "Original" });
          } else if (dub.subjectId) {
            languages.push({ id: dub.subjectId, language: dub.lanName || "Dub" });
          }
        });
      }
      if (!languages.some(function (item) { return String(item.id) === String(match.subjectId); })) {
        languages.unshift({ id: match.subjectId, language: "Original" });
      }
      var output = [];
      var chain = Promise.resolve();
      languages.forEach(function (language) {
        chain = chain.then(function () {
          var playUrl = API_BASE + "/wefeed-mobile-bff/subject-api/play-info?subjectId=" +
            encodeURIComponent(language.id) + "&se=" + season + "&ep=" + episode;
          return request("GET", playUrl, null, authHeaders).then(function (playResponse) {
            var playData = playResponse.data && playResponse.data.data;
            if (!playData) return;
            var streams = Array.isArray(playData.streams) ? playData.streams : [];
            var candidates = streams.map(function (stream) {
              return {
                url: stream.url,
                quality: qualityLabel(stream),
                id: stream.id || String(language.id) + "|" + season + "|" + episode,
                cookie: stream.signCookie || ""
              };
            }).filter(function (stream) { return !!stream.url; });

            if (!candidates.length && Array.isArray(playData.resourceDetectors)) {
              playData.resourceDetectors.forEach(function (detector) {
                (detector.resolutionList || []).forEach(function (video) {
                  if (video.resourceLink) candidates.push({
                    url: video.resourceLink,
                    quality: video.resolution ? String(video.resolution) + "p" : "Auto",
                    id: String(language.id) + "|" + (video.se || season) + "|" + (video.ep || episode),
                    cookie: ""
                  });
                });
              });
            }

            var captionChain = Promise.resolve();
            candidates.forEach(function (candidate) {
              captionChain = captionChain.then(function () {
                return getCaptions(language.id, candidate.id, authHeaders, language.language).catch(function () { return []; }).then(function (captions) {
                  var format = /\.mpd(?:[?#]|$)/i.test(candidate.url) ? "DASH" :
                    (/\.m3u8(?:[?#]|$)/i.test(candidate.url) ? "HLS" :
                    (/\.mp4(?:[?#]|$)/i.test(candidate.url) ? "MP4" : "VIDEO"));
                  var headers = {
                    Referer: API_BASE,
                    "User-Agent": "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; MovieBox; Build/BP22.250325.006; Cronet/133.0.6876.3)"
                  };
                  if (candidate.cookie) headers.Cookie = candidate.cookie;
                  output.push({
                    name: "MovieBox",
                    title: title + (type === "tv" ? " S" + season + "E" + episode : "") +
                      " (" + language.language + ") - " + candidate.quality + " [" + format + "]",
                    url: candidate.url,
                    quality: candidate.quality,
                    headers: headers,
                    subtitles: captions,
                    provider: "moviebox"
                  });
                });
              });
            });
            return captionChain;
          });
        }).catch(function (error) {
          console.error("[MovieBox] Source " + language.language + " failed: " + error.message);
        });
      });
      return chain.then(function () { return output; });
    });
  });
}

module.exports = { getStreams: getStreams };
