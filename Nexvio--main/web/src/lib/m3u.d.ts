export type ParsedTvChannel = {
  id: string
  name: string
  url: string
  logo?: string
  group?: string
  favorite: boolean
}
export type M3uParseResult = { channels: ParsedTvChannel[]; error?: string }
export function parseM3uPlaylist(input: string): M3uParseResult
