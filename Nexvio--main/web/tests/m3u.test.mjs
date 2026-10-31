import test from 'node:test'
import assert from 'node:assert/strict'
import { parseM3uPlaylist } from '../src/lib/m3u.js'

test('rejects blank and non-M3U input', () => {
  assert.match(parseM3uPlaylist('').error || '', /empty/i)
  assert.match(parseM3uPlaylist('not a playlist').error || '', /valid M3U/i)
})

test('parses channel name, logo, group, and HTTP stream URL', () => {
  const parsed = parseM3uPlaylist('#EXTM3U\n#EXTINF:-1 tvg-name="News" tvg-logo="https://cdn.example/logo.png" group-title="News, World",News World\nhttps://media.example/live.m3u8')
  assert.equal(parsed.channels.length, 1)
  assert.equal(parsed.channels[0].name, 'News')
  assert.equal(parsed.channels[0].logo, 'https://cdn.example/logo.png')
  assert.equal(parsed.channels[0].group, 'News, World')
  assert.equal(parsed.channels[0].url, 'https://media.example/live.m3u8')
})

test('ignores invalid URLs and deduplicates repeated channels', () => {
  const parsed = parseM3uPlaylist('#EXTM3U\n#EXTINF:-1,Channel\nfile:///private/video.mp4\n#EXTINF:-1,Channel\nhttps://media.example/live.m3u8\n#EXTINF:-1,Channel\nhttps://media.example/live.m3u8')
  assert.equal(parsed.channels.length, 1)
  assert.equal(parsed.channels[0].url, 'https://media.example/live.m3u8')
})
