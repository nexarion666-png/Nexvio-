import type { SupabaseClient } from '@supabase/supabase-js'

export type CloudTitle = {
  id: number
  title?: string
  name?: string
  overview?: string
  poster_path?: string | null
  posterPath?: string | null
  backdrop_path?: string | null
  backdropPath?: string | null
  vote_average?: number
  voteAverage?: number
  release_date?: string
  releaseDate?: string
  first_air_date?: string
  firstAirDate?: string
  media_type?: 'movie' | 'tv'
  mediaType?: 'movie' | 'tv'
  genre_ids?: number[]
  genreIds?: number[]
}

type StateRow = { state_data: Record<string, unknown> | null }

const WATCHLIST_KEY = 'watchlist'

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}

function parseItems(value: unknown): CloudTitle[] {
  if (Array.isArray(value)) return value.filter(isCloudTitle).map(normalizeTitle)
  if (typeof value === 'string') {
    try {
      const parsed: unknown = JSON.parse(value)
      return Array.isArray(parsed) ? parsed.filter(isCloudTitle).map(normalizeTitle) : []
    } catch {
      return []
    }
  }
  return []
}

function isCloudTitle(value: unknown): value is CloudTitle {
  return value !== null && typeof value === 'object' &&
    typeof (value as { id?: unknown }).id === 'number'
}

function normalizeTitle(item: CloudTitle): CloudTitle {
  const mediaType = item.mediaType ?? item.media_type ??
    (item.firstAirDate || item.first_air_date ? 'tv' : 'movie')
  return {
    ...item,
    media_type: mediaType,
    mediaType,
    poster_path: item.poster_path ?? item.posterPath ?? null,
    posterPath: item.posterPath ?? item.poster_path ?? null,
    backdrop_path: item.backdrop_path ?? item.backdropPath ?? null,
    backdropPath: item.backdropPath ?? item.backdrop_path ?? null,
    vote_average: item.vote_average ?? item.voteAverage ?? 0,
    voteAverage: item.voteAverage ?? item.vote_average ?? 0,
    release_date: item.release_date ?? item.releaseDate ?? '',
    releaseDate: item.releaseDate ?? item.release_date ?? '',
    first_air_date: item.first_air_date ?? item.firstAirDate ?? '',
    firstAirDate: item.firstAirDate ?? item.first_air_date ?? '',
    genre_ids: item.genre_ids ?? item.genreIds ?? [],
    genreIds: item.genreIds ?? item.genre_ids ?? [],
  }
}

function toAndroidTitle(item: CloudTitle): Record<string, unknown> {
  const normalized = normalizeTitle(item)
  // Keep Android's camelCase fields and TMDB's snake_case fields so both clients can
  // read the same item without a schema migration.
  return {
    ...normalized,
    id: normalized.id,
    mediaType: normalized.mediaType,
    title: normalized.title ?? normalized.name ?? 'Untitled',
    name: normalized.name ?? normalized.title ?? 'Untitled',
    posterPath: normalized.posterPath ?? null,
    backdropPath: normalized.backdropPath ?? null,
    voteAverage: normalized.voteAverage ?? 0,
    releaseDate: normalized.releaseDate ?? '',
    firstAirDate: normalized.firstAirDate ?? '',
    genreIds: normalized.genreIds ?? [],
  }
}

async function ensurePrimaryProfile(
  client: SupabaseClient,
  accountId: string,
  profileId = 'primary',
): Promise<void> {
  const { data, error } = await client
    .from('nexvio_profiles')
    .select('profile_id,profile_data')
    .eq('account_id', accountId)
    .eq('profile_id', profileId)
    .maybeSingle()
  if (error) throw error
  if (data) return

  const now = Date.now()
  const profile = {
    id: profileId,
    name: profileId === 'primary' ? 'Main Profile' : 'Profile',
    avatar: 'fox',
    customAvatar: '',
    primary: profileId === 'primary',
    primaryAddons: true,
    createdAt: now,
    updatedAt: now,
  }
  const { error: upsertError } = await client
    .from('nexvio_profiles')
    .upsert({
      account_id: accountId,
      profile_id: profileId,
      profile_data: profile,
      updated_at: new Date(now).toISOString(),
    }, { onConflict: 'account_id,profile_id' })
  if (upsertError) throw upsertError
}

export async function loadCloudWatchlist(
  client: SupabaseClient,
  accountId: string,
  profileId = 'primary',
): Promise<CloudTitle[]> {
  await ensurePrimaryProfile(client, accountId, profileId)
  const { data, error } = await client
    .from('nexvio_profile_state')
    .select('state_data')
    .eq('account_id', accountId)
    .eq('profile_id', profileId)
    .eq('state_key', WATCHLIST_KEY)
    .maybeSingle()
  if (error) throw error
  return parseItems(asRecord((data as StateRow | null)?.state_data).items)
}

export async function saveCloudWatchlist(
  client: SupabaseClient,
  accountId: string,
  items: CloudTitle[],
  profileId = 'primary',
): Promise<void> {
  await ensurePrimaryProfile(client, accountId, profileId)
  const { data, error } = await client
    .from('nexvio_profile_state')
    .select('state_data')
    .eq('account_id', accountId)
    .eq('profile_id', profileId)
    .eq('state_key', WATCHLIST_KEY)
    .maybeSingle()
  if (error) throw error

  const existing = asRecord((data as StateRow | null)?.state_data)
  const androidItems = items.map(toAndroidTitle)
  // Android SharedPreferences stores the "items" preference as a JSON string.
  // Keep that representation so the existing Android sync baseline remains compatible.
  const stateData = { ...existing, items: JSON.stringify(androidItems) }
  const { error: upsertError } = await client
    .from('nexvio_profile_state')
    .upsert({
      account_id: accountId,
      profile_id: profileId,
      state_key: WATCHLIST_KEY,
      state_data: stateData,
      updated_at: new Date().toISOString(),
    }, { onConflict: 'account_id,profile_id,state_key' })
  if (upsertError) throw upsertError
}
