/**
 * @typedef {{id:string,name:string,url:string,logo?:string,group?:string,favorite:boolean}} ParsedTvChannel
 * @typedef {{channels:ParsedTvChannel[],error?:string}} M3uParseResult
 */

/** Parse the channel metadata subset used by Nexvio's Android M3uParser. @param {string} input @returns {M3uParseResult} */
export function parseM3uPlaylist(input) {
  if (!input.trim()) return { channels: [], error: 'The playlist is empty.' }
  const lines = input.replace(/^\uFEFF/, '').split(/\r?\n/).map(line => line.trim()).filter(Boolean)
  if (!lines.some(line => line.toUpperCase() === '#EXTM3U')) {
    return { channels: [], error: 'This file does not appear to be a valid M3U playlist.' }
  }
  /** @type {ParsedTvChannel[]} */
  const channels = []
  /** @type {{name:string,logo?:string,group?:string}|null} */
  let pending = null
  for (const line of lines) {
    if (line.toUpperCase().startsWith('#EXTINF:')) {
      let comma = -1
      let quoted = false
      for (let i = 0; i < line.length; i++) {
        if (line[i] === '"') quoted = !quoted
        else if (line[i] === ',' && !quoted) { comma = i; break }
      }
      const metadata = comma >= 0 ? line.slice(0, comma) : line
      const label = comma >= 0 ? line.slice(comma + 1).trim() : ''
      const attr = key => metadata.match(new RegExp(`(?:^|\\s)${key}\\s*=\\s*"([^"]*)"`, 'i'))?.[1]
      const duration = metadata.slice(metadata.indexOf(':') + 1).trim().split(/\s+/, 1)[0]
      pending = duration ? { name: attr('tvg-name') || label, logo: attr('tvg-logo'), group: attr('group-title') } : null
    } else if (!line.startsWith('#') && pending) {
      try {
        const url = new URL(line)
        if ((url.protocol === 'http:' || url.protocol === 'https:') && url.hostname && pending.name) {
          channels.push({ id: `${pending.name.toLowerCase()}|${url.toString()}`, name: pending.name, url: url.toString(), logo: pending.logo, group: pending.group, favorite: false })
        }
      } catch { /* Skip malformed URL entries, as Android does. */ }
      pending = null
    }
  }
  const unique = [...new Map(channels.map(channel => [channel.id, channel])).values()]
  return unique.length ? { channels: unique } : { channels: [], error: 'No valid HTTP or HTTPS channels were found in this playlist.' }
}
