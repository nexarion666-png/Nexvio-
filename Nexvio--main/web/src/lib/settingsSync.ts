import type { SupabaseClient } from '@supabase/supabase-js'

type StateRow = { state_data: Record<string, unknown> | null }

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}

export async function loadCloudSettings(
  client: SupabaseClient,
  accountId: string,
  profileId = 'primary',
): Promise<Record<string, unknown>> {
  const { data, error } = await client
    .from('nexvio_profile_state')
    .select('state_data')
    .eq('account_id', accountId)
    .eq('profile_id', profileId)
    .eq('state_key', 'settings')
    .maybeSingle()
  if (error) throw error
  return asRecord((data as StateRow | null)?.state_data)
}

export async function saveCloudSettings(
  client: SupabaseClient,
  accountId: string,
  settings: Record<string, unknown>,
  profileId = 'primary',
): Promise<void> {
  const { data, error } = await client
    .from('nexvio_profile_state')
    .select('state_data')
    .eq('account_id', accountId)
    .eq('profile_id', profileId)
    .eq('state_key', 'settings')
    .maybeSingle()
  if (error) throw error

  // Merge only web-owned preference keys so unknown Android settings survive.
  const existing = asRecord((data as StateRow | null)?.state_data)
  const stateData = { ...existing, ...settings }
  const { error: upsertError } = await client
    .from('nexvio_profile_state')
    .upsert({
      account_id: accountId,
      profile_id: profileId,
      state_key: 'settings',
      state_data: stateData,
      updated_at: new Date().toISOString(),
    }, { onConflict: 'account_id,profile_id,state_key' })
  if (upsertError) throw upsertError
}
