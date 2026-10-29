import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import type { Session } from '@supabase/supabase-js'
import {
  ArrowLeft, Bookmark, Check, ChevronDown, CircleUserRound, Clapperboard,
  Film, Heart, Info, LoaderCircle, LogOut, Play, Search, ShieldCheck,
  Sparkles, Tv, X, Radio, Library, UserRound, Settings, Upload, Trash2,
} from 'lucide-react'
import { supabase, supabaseConfigMissing } from './lib/supabase'
import { loadCloudWatchlist, saveCloudWatchlist } from './lib/watchlistSync'
import { loadCloudSettings, saveCloudSettings } from './lib/settingsSync'
import { parseM3uPlaylist } from './lib/m3u'
import Hls from 'hls.js'
import './styles.css'

type MediaKind = 'movie' | 'tv'
type Title = {
  id: number
  title: string
  overview: string
  poster_path: string | null
  backdrop_path: string | null
  vote_average: number
  release_date?: string
  first_air_date?: string
  media_type?: MediaKind
  genre_ids?: number[]
}
type AuthMode = 'signin' | 'signup' | 'reset' | 'update-password'
type NexvioProfile = { id: string; name: string; avatar?: string; primary?: boolean; createdAt?: number }
type TvChannel = { id: string; name: string; url: string; logo?: string; group?: string; favorite: boolean }

const TMDB_KEY = import.meta.env.VITE_TMDB_API_KEY as string | undefined
const TMDB = 'https://api.themoviedb.org/3'
const IMAGE = 'https://image.tmdb.org/t/p/'
const imageUrl = (path: string | null | undefined, size = 'w500') => path ? IMAGE + size + path : ''
const titleName = (item: Title) => item.title || (item as Title & { name?: string }).name || 'Untitled'
const yearOf = (item: Title) => (item.release_date || item.first_air_date || '').slice(0, 4)
const kindOf = (item: Title): MediaKind => item.media_type || ('first_air_date' in item && item.first_air_date ? 'tv' : 'movie')

async function tmdb<T>(path: string): Promise<T> {
  if (!TMDB_KEY) throw new Error('Add VITE_TMDB_API_KEY in your Vercel environment variables to load the catalogue.')
  const response = await fetch(`${TMDB}${path}${path.includes('?') ? '&' : '?'}api_key=${encodeURIComponent(TMDB_KEY)}`)
  if (!response.ok) throw new Error(`The catalogue service returned HTTP ${response.status}.`)
  return response.json() as Promise<T>
}

function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [authReady, setAuthReady] = useState(false)
  const [authMode, setAuthMode] = useState<AuthMode>('signin')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [authBusy, setAuthBusy] = useState(false)
  const [authMessage, setAuthMessage] = useState('')
  const [authError, setAuthError] = useState('')
  const [activeTab, setActiveTab] = useState('Home')
  const [profiles, setProfiles] = useState<NexvioProfile[]>([])
  const [activeProfileId, setActiveProfileId] = useState('primary')
  const [profileName, setProfileName] = useState('')
  const [profileAvatar, setProfileAvatar] = useState('fox')
  const [profileEditingId, setProfileEditingId] = useState<string | null>(null)
  const [profileError, setProfileError] = useState('')
  const [profileBusy, setProfileBusy] = useState(false)
  const [tvChannels, setTvChannels] = useState<TvChannel[]>([])
  const [tvError, setTvError] = useState('')
  const [tvFilter, setTvFilter] = useState('')
  const [playingChannel, setPlayingChannel] = useState<TvChannel | null>(null)
  const [playerError, setPlayerError] = useState('')
  const [query, setQuery] = useState('')
  const [searchText, setSearchText] = useState('')
  const [featured, setFeatured] = useState<Title | null>(null)
  const [selected, setSelected] = useState<Title | null>(null)
  const [providerPage, setProviderPage] = useState<'content' | 'addons' | 'repositories' | null>(null)
  const [streamRequest, setStreamRequest] = useState<{ item: Title; season: number; episode: number } | null>(null)
  const [rows, setRows] = useState<{ title: string; items: Title[] }[]>([])
  const [loading, setLoading] = useState(false)
  const [catalogueError, setCatalogueError] = useState('')
  const [watchlist, setWatchlist] = useState<Title[]>([])
  const [watchlistReadyFor, setWatchlistReadyFor] = useState<string | null>(null)
  const [watchlistStatus, setWatchlistStatus] = useState('')
  const [watchlistError, setWatchlistError] = useState('')
  const [showAccount, setShowAccount] = useState(false)
  const [settingsReadyFor, setSettingsReadyFor] = useState<string | null>(null)
  const [settingsStatus, setSettingsStatus] = useState('')
  const [settingsError, setSettingsError] = useState('')
  const [webSettings, setWebSettings] = useState({ reduceMotion: false, theme: 'Emerald', amoledBlack: false, playbackAutoplay: true, videoSizing: 'Fit', audioLanguage: 'Device language', subtitleLanguage: 'Device language', subtitleSize: 18 })

  useEffect(() => {
    if (!supabase) { setAuthReady(true); return }
    const { data } = supabase.auth.onAuthStateChange((event, nextSession) => {
      setSession(nextSession)
      setActiveProfileId('primary')
      if (event === 'PASSWORD_RECOVERY') {
        setAuthMode('update-password')
        setPassword('')
        setAuthError('')
        setAuthMessage('')
      }
    })
    supabase.auth.getSession().then(({ data: sessionData, error }) => {
      if (error) throw error
      setSession(sessionData.session)
    }).catch((error: unknown) => {
      setAuthError(error instanceof Error ? error.message : 'Could not restore your account session.')
    }).finally(() => setAuthReady(true))
    return () => data.subscription.unsubscribe()
  }, [])

  const loadCatalogue = useCallback(async () => {
    setLoading(true)
    setCatalogueError('')
    try {
      const [trending, movies, series, topMovies] = await Promise.all([
        tmdb<{ results: Title[] }>('/trending/all/week?language=en-US'),
        tmdb<{ results: Title[] }>('/movie/popular?language=en-US&page=1'),
        tmdb<{ results: Title[] }>('/tv/popular?language=en-US&page=1'),
        tmdb<{ results: Title[] }>('/movie/top_rated?language=en-US&page=1'),
      ])
      const clean = (items: Title[]) => items.filter(item => item.poster_path)
      const trendItems = clean(trending.results).map(item => ({ ...item, media_type: item.media_type || kindOf(item) }))
      setFeatured(trendItems.find(item => item.backdrop_path) || clean(movies.results)[0] || null)
      setRows([
        { title: 'Trending This Week', items: trendItems },
        { title: 'Popular Movies', items: clean(movies.results).map(item => ({ ...item, media_type: 'movie' })) },
        { title: 'Popular Series', items: clean(series.results).map(item => ({ ...item, media_type: 'tv' })) },
        { title: 'Top Rated Movies', items: clean(topMovies.results).map(item => ({ ...item, media_type: 'movie' })) },
      ])
    } catch (error) {
      setCatalogueError(error instanceof Error ? error.message : 'Could not load the catalogue.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void loadCatalogue() }, [loadCatalogue])

  useEffect(() => {
    const accountId = session?.user.id
    let cancelled = false
    setWatchlist([])
    setWatchlistReadyFor(null)
    setWatchlistError('')
    if (!accountId || !supabase) {
      setWatchlistStatus('Sign in to sync My List.')
      return () => { cancelled = true }
    }
    setWatchlistStatus('Loading cloud list…')
    void loadCloudWatchlist(supabase, accountId, activeProfileId).then(items => {
      if (cancelled) return
      setWatchlist(items as Title[])
      setWatchlistReadyFor(`${accountId}:${activeProfileId}`)
      setWatchlistStatus('Cloud list synced')
    }).catch((error: unknown) => {
      if (cancelled) return
      setWatchlistError(error instanceof Error ? error.message : 'Could not load your cloud list.')
      setWatchlistStatus('Cloud sync failed')
    })
    return () => { cancelled = true }
  }, [session?.user.id, activeProfileId])

  useEffect(() => {
    const accountId = session?.user.id
    if (!accountId || !supabase || watchlistReadyFor !== `${accountId}:${activeProfileId}`) return
    let cancelled = false
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const { data: current } = await supabase!.auth.getSession()
          if (cancelled || current.session?.user.id !== accountId) return
          setWatchlistStatus('Saving cloud list…')
          setWatchlistError('')
          await saveCloudWatchlist(supabase!, accountId, watchlist, activeProfileId)
          const { data: afterSave } = await supabase!.auth.getSession()
          if (cancelled || afterSave.session?.user.id !== accountId) return
          setWatchlistStatus('Cloud list synced')
        } catch (error) {
          if (cancelled) return
          setWatchlistError(error instanceof Error ? error.message : 'Could not save your cloud list.')
          setWatchlistStatus('Cloud sync failed')
        }
      })()
    }, 450)
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [watchlist, session?.user.id, watchlistReadyFor, activeProfileId])

  useEffect(() => {
    const accountId = session?.user.id
    let cancelled = false
    setSettingsReadyFor(null)
    setSettingsError('')
    if (!accountId || !supabase) return () => { cancelled = true }
    setSettingsStatus('Loading preferences…')
    void loadCloudSettings(supabase, accountId, activeProfileId).then(remote => {
      if (cancelled) return
      setWebSettings({
        reduceMotion: remote.web_reduce_motion === true,
        theme: typeof remote.web_theme === 'string' && ['Emerald', 'White', 'Crimson', 'Ocean', 'Violet', 'Amber', 'Rose'].includes(remote.web_theme) ? remote.web_theme : 'Emerald',
        amoledBlack: remote.web_amoled_black === true,
        playbackAutoplay: remote.playback_autoplay !== false,
        videoSizing: ['Fit', 'Fill', 'Zoom'].includes(String(remote.playback_resize)) ? String(remote.playback_resize) : 'Fit',
        audioLanguage: typeof remote.preferred_audio_language === 'string' ? remote.preferred_audio_language : 'Device language',
        subtitleLanguage: typeof remote.preferred_subtitle_language === 'string' ? remote.preferred_subtitle_language : 'Device language',
        subtitleSize: typeof remote.subtitle_size_sp === 'number' ? Math.min(36, Math.max(12, remote.subtitle_size_sp)) : 18,
      })
      setSettingsReadyFor(`${accountId}:${activeProfileId}`)
      setSettingsStatus('Preferences synced')
    }).catch((error: unknown) => {
      if (cancelled) return
      setSettingsError(error instanceof Error ? error.message : 'Could not load preferences.')
      setSettingsStatus('Preferences sync failed')
    })
    return () => { cancelled = true }
  }, [session?.user.id, activeProfileId])

  useEffect(() => {
    const accountId = session?.user.id
    if (!accountId || !supabase || settingsReadyFor !== `${accountId}:${activeProfileId}`) return
    let cancelled = false
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const { data: before } = await supabase!.auth.getSession()
          if (cancelled || before.session?.user.id !== accountId) return
          setSettingsStatus('Saving preferences…')
          setSettingsError('')
          await saveCloudSettings(supabase!, accountId, {
            web_reduce_motion: webSettings.reduceMotion,
            web_theme: webSettings.theme,
            web_amoled_black: webSettings.amoledBlack,
            playback_autoplay: webSettings.playbackAutoplay,
            playback_resize: webSettings.videoSizing,
            preferred_audio_language: webSettings.audioLanguage,
            preferred_subtitle_language: webSettings.subtitleLanguage,
            subtitle_size_sp: webSettings.subtitleSize,
          }, activeProfileId)
          const { data: after } = await supabase!.auth.getSession()
          if (cancelled || after.session?.user.id !== accountId) return
          setSettingsStatus('Preferences synced')
        } catch (error) {
          if (cancelled) return
          setSettingsError(error instanceof Error ? error.message : 'Could not save preferences.')
          setSettingsStatus('Preferences sync failed')
        }
      })()
    }, 400)
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [webSettings, session?.user.id, settingsReadyFor, activeProfileId])

  useEffect(() => {
    document.documentElement.dataset.reduceMotion = webSettings.reduceMotion ? 'true' : 'false'
    const themeColors: Record<string, string> = {
      Emerald: '#38c77a', White: '#f5f5f5', Crimson: '#e53950',
      Ocean: '#3298f5', Violet: '#9b70f5', Amber: '#ffa43a', Rose: '#e85d9e',
    }
    document.documentElement.style.setProperty('--lime', themeColors[webSettings.theme] || themeColors.Emerald)
    document.documentElement.style.setProperty('--bg', webSettings.amoledBlack ? '#000000' : '#101114')
    document.documentElement.dataset.amoledBlack = webSettings.amoledBlack ? 'true' : 'false'
  }, [webSettings.reduceMotion, webSettings.theme, webSettings.amoledBlack])

  useEffect(() => {
    const accountId = session?.user.id
    if (!accountId || !supabase) { setProfiles([]); setActiveProfileId('primary'); return }
    let cancelled = false
    void (async () => {
      try {
        const { data, error } = await supabase!.from('nexvio_profiles').select('profile_id,profile_data,updated_at').eq('account_id', accountId).order('updated_at', { ascending: true })
        if (error) throw error
        let found = (data || []).map((row: { profile_id: string; profile_data: Record<string, unknown> | null }) => ({
          id: row.profile_id,
          name: String(row.profile_data?.name || (row.profile_id === 'primary' ? 'Main Profile' : 'Profile')),
          avatar: String(row.profile_data?.avatar || 'fox'),
          primary: row.profile_id === 'primary' || row.profile_data?.primary === true,
        }))
        if (!found.some(profile => profile.id === 'primary')) {
          const now = Date.now()
          const primary = { id: 'primary', name: 'Main Profile', avatar: 'fox', primary: true, primaryAddons: true, createdAt: now, updatedAt: now }
          const { error: insertError } = await supabase!.from('nexvio_profiles').upsert({ account_id: accountId, profile_id: 'primary', profile_data: primary, updated_at: new Date(now).toISOString() }, { onConflict: 'account_id,profile_id' })
          if (insertError) throw insertError
          found = [ { id: 'primary', name: 'Main Profile', avatar: 'fox', primary: true }, ...found ]
        }
        if (!cancelled) {
          setProfiles(found)
          const saved = window.localStorage.getItem(`nexvio_active_profile_${accountId}`)
          setActiveProfileId(found.some(profile => profile.id === saved) ? saved! : 'primary')
        }
      } catch (error) {
        if (!cancelled) setProfileError(error instanceof Error ? error.message : 'Could not load profiles.')
      }
    })()
    return () => { cancelled = true }
  }, [session?.user.id])

  useEffect(() => {
    const accountId = session?.user.id
    const key = `nexvio_iptv_${accountId || 'guest'}_${activeProfileId}`
    try { setTvChannels(JSON.parse(window.localStorage.getItem(key) || '[]') as TvChannel[]) }
    catch { setTvChannels([]) }
  }, [session?.user.id, activeProfileId])

  useEffect(() => {
    const accountId = session?.user.id
    const key = `nexvio_iptv_${accountId || 'guest'}_${activeProfileId}`
    window.localStorage.setItem(key, JSON.stringify(tvChannels))
  }, [tvChannels, session?.user.id, activeProfileId])

  const saveProfile = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!supabase || !session || !profileName.trim()) return
    if (profiles.some(profile => profile.id !== profileEditingId && profile.name.trim().toLowerCase() === profileName.trim().toLowerCase())) { setProfileError('A profile with that name already exists.'); return }
    setProfileBusy(true); setProfileError('')
    try {
      const now = Date.now()
      if (profileEditingId) {
        const { data, error: readError } = await supabase.from('nexvio_profiles').select('profile_data').eq('account_id', session.user.id).eq('profile_id', profileEditingId).single()
        if (readError) throw readError
        const oldProfile = data?.profile_data && typeof data.profile_data === 'object' ? data.profile_data as Record<string, unknown> : {}
        const updated = { ...oldProfile, id: profileEditingId, name: profileName.trim(), avatar: profileAvatar, updatedAt: now }
        const { error } = await supabase.from('nexvio_profiles').update({ profile_data: updated, updated_at: new Date(now).toISOString() }).eq('account_id', session.user.id).eq('profile_id', profileEditingId)
        if (error) throw error
        setProfiles(current => current.map(profile => profile.id === profileEditingId ? { ...profile, name: profileName.trim(), avatar: profileAvatar } : profile))
      } else {
        const id = crypto.randomUUID()
        const profile = { id, name: profileName.trim(), avatar: profileAvatar, customAvatar: '', primary: false, primaryAddons: true, createdAt: now, updatedAt: now }
        const { error } = await supabase.from('nexvio_profiles').insert({ account_id: session.user.id, profile_id: id, profile_data: profile, updated_at: new Date(now).toISOString() })
        if (error) throw error
        setProfiles(current => [...current, { id, name: profile.name, avatar: profileAvatar }])
        setActiveProfileId(id)
        window.localStorage.setItem(`nexvio_active_profile_${session.user.id}`, id)
      }
      setProfileName('')
      setProfileAvatar('fox')
      setProfileEditingId(null)
    } catch (error) { setProfileError(error instanceof Error ? error.message : 'Could not save profile.') }
    finally { setProfileBusy(false) }
  }

  const editProfile = (profile: NexvioProfile) => {
    setProfileEditingId(profile.id)
    setProfileName(profile.name)
    setProfileAvatar(profile.avatar || 'fox')
    setProfileError('')
    document.getElementById('profile-name')?.focus()
  }

  const selectProfile = (id: string) => {
    setActiveProfileId(id)
    if (session) window.localStorage.setItem(`nexvio_active_profile_${session.user.id}`, id)
  }

  const importPlaylistText = (text: string) => {
    const result = parseM3uPlaylist(text)
    if (result.error) { setTvError(result.error); return }
    setTvError('')
    setTvChannels(current => {
      const existing = new Set(current.map(channel => channel.id))
      return [...current, ...result.channels.filter(channel => !existing.has(channel.id))]
    })
  }

  const doAuth = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!supabase) return
    setAuthBusy(true); setAuthError(''); setAuthMessage('')
    try {
      if (authMode === 'signup') {
        const { data, error } = await supabase.auth.signUp({
          email: email.trim(),
          password,
          options: { emailRedirectTo: window.location.origin },
        })
        if (error) throw error
        setAuthMessage(data.session ? 'Your Nexvio account is ready.' : 'Check your email to confirm your account, then sign in.')
      } else if (authMode === 'reset') {
        const { error } = await supabase.auth.resetPasswordForEmail(email.trim(), {
          redirectTo: window.location.origin,
        })
        if (error) throw error
        setAuthMessage('If an account exists for that email, a password-reset message has been sent.')
      } else if (authMode === 'update-password') {
        const { error } = await supabase.auth.updateUser({ password })
        if (error) throw error
        await supabase.auth.signOut()
        setPassword('')
        setAuthMode('signin')
        setAuthMessage('Your password has been updated. Sign in with your new password.')
      } else {
        const { error } = await supabase.auth.signInWithPassword({ email: email.trim(), password })
        if (error) throw error
      }
    } catch (error) {
      setAuthError(error instanceof Error ? error.message : 'Authentication failed.')
    } finally { setAuthBusy(false) }
  }

  const signOut = async () => {
    if (!supabase) return
    await supabase.auth.signOut()
    setShowAccount(false)
  }

  const openTitle = (item: Title) => {
    setSelected(item)
    window.history.pushState({ nexvioView: 'title', id: item.id, kind: kindOf(item) }, '', `#title-${kindOf(item)}-${item.id}`)
  }
  const closeTitle = () => {
    setSelected(null)
    if (window.history.state?.nexvioView === 'title') window.history.back()
    else if (window.location.hash.startsWith('#title-')) window.history.replaceState(null, '', window.location.pathname + window.location.search)
  }

  useEffect(() => {
    const onPopState = () => {
      if (window.history.state?.nexvioView !== 'title') setSelected(null)
      if (window.history.state?.nexvioView !== 'player') setPlayingChannel(null)
    }
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [])

  const toggleWatchlist = (item: Title) => {
    setWatchlist(current => current.some(saved => saved.id === item.id && kindOf(saved) === kindOf(item))
      ? current.filter(saved => !(saved.id === item.id && kindOf(saved) === kindOf(item)))
      : [...current, item])
  }

  const searchCatalogue = async (value: string) => {
    const cleaned = value.trim()
    if (!cleaned) { setSearchText(''); setRows([]); return }
    setLoading(true); setCatalogueError(''); setSearchText(cleaned); setActiveTab('Search')
    try {
      const result = await tmdb<{ results: Title[] }>(`/search/multi?language=en-US&include_adult=false&query=${encodeURIComponent(cleaned)}&page=1`)
      const recommendations = result.results
        .filter(item => item.poster_path && (item.media_type === 'movie' || item.media_type === 'tv'))
        .map(item => ({ ...item, media_type: item.media_type as MediaKind }))
      setRows([{ title: `Recommendations for “${cleaned}”`, items: recommendations }])
    } catch (error) { setCatalogueError(error instanceof Error ? error.message : 'Search failed.') } finally { setLoading(false) }
  }

  useEffect(() => {
    if (activeTab !== 'Search') return
    const cleaned = query.trim()
    if (cleaned.length < 2) {
      setSearchText('')
      setRows([])
      setCatalogueError('')
      setLoading(false)
      return
    }
    const timer = window.setTimeout(() => { void searchCatalogue(cleaned) }, 300)
    return () => window.clearTimeout(timer)
  }, [query, activeTab])

  const doSearch = async (event: React.FormEvent) => { event.preventDefault(); await searchCatalogue(query) }

  const visibleRows = useMemo(() => {
    if (activeTab === 'Movies') return rows
      .map(row => ({ ...row, items: row.items.filter(item => kindOf(item) === 'movie') }))
      .filter(row => row.items.length > 0)
    if (activeTab === 'Series') return rows
      .map(row => ({ ...row, items: row.items.filter(item => kindOf(item) === 'tv') }))
      .filter(row => row.items.length > 0)
    if (activeTab === 'My List') return [{ title: 'My List', items: watchlist }]
    return rows
  }, [activeTab, rows, watchlist])

  if (!authReady) return <div className="boot-screen"><div className="brand-mark">N</div><LoaderCircle className="spin" size={22} /></div>
  if (!session || authMode === 'update-password') return <AuthScreen
    mode={authMode} setMode={setAuthMode} email={email} setEmail={setEmail}
    password={password} setPassword={setPassword} busy={authBusy}
    error={authError} message={authMessage} setAuthError={setAuthError} setAuthMessage={setAuthMessage} onSubmit={doAuth}
  />

  return <div className="app-shell">
    <header className="topbar">
      <button className="brand" onClick={() => { setActiveTab('Home'); setSearchText('') }} aria-label="Nexvio home"><span className="brand-mark">N</span><span className="brand-copy"><strong>NEXVIO</strong><small>Movies · Series · Live TV</small></span></button>
      <nav className="desktop-nav" aria-label="Main navigation">
        {[{tab:'Home', icon:Clapperboard}, {tab:'Movies', icon:Film}, {tab:'Series', icon:Tv}, {tab:'Search', icon:Search}, {tab:'Live TV', icon:Radio}, {tab:'Library', icon:Library}, {tab:'Profile', icon:UserRound}].map(({tab, icon: NavIcon}) => <button key={tab} className={activeTab === tab ? 'nav-item active' : 'nav-item'} onClick={() => { setActiveTab(tab); setSearchText(''); setSelected(null) }}><NavIcon size={15} />{tab}</button>)}
      </nav>
      <form className="search-box" onSubmit={doSearch}><Search size={17} /><input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search titles" aria-label="Search titles" onFocus={() => { if (activeTab !== 'Search') setActiveTab('Search') }} /><button type="submit" aria-label="Submit search"><ArrowLeft className="search-enter" size={16} /></button></form>
      <div className="account-wrap">
        <button className="account-button" onClick={() => setShowAccount(value => !value)} aria-label="Account menu"><CircleUserRound size={21} /><ChevronDown size={14} /></button>
        {showAccount && <div className="account-menu"><div className="account-email">{session.user.email}</div><div className="account-note"><ShieldCheck size={14} /> Same Nexvio Supabase account</div><button onClick={() => { setActiveTab('Profile'); setShowAccount(false) }}><ShieldCheck size={15} /> Preferences</button><button onClick={signOut}><LogOut size={15} /> Sign out</button></div>}
      </div>
    </header>

    <main>
      {activeTab === 'Home' && !searchText && featured && <section className="hero" style={{ backgroundImage: `linear-gradient(90deg, #080b0a 0%, rgba(8,11,10,.91) 28%, rgba(8,11,10,.24) 68%, rgba(8,11,10,.15) 100%), linear-gradient(0deg, #080b0a 0%, transparent 58%), url("${imageUrl(featured.backdrop_path, 'original')}")` }}>
        <div className="hero-content"><div className="eyebrow"><Sparkles size={14} /> FEATURED TODAY</div><h1>{titleName(featured)}</h1><div className="hero-meta"><span className="rating">★ {featured.vote_average.toFixed(1)}</span><span>{yearOf(featured)}</span><span className="tag">TMDB</span><span>{kindOf(featured) === 'tv' ? 'Series' : 'Movie'}</span></div><p>{featured.overview || 'Discover something new to watch.'}</p><div className="hero-actions"><button className="primary-button" onClick={() => openTitle(featured)}><Play size={17} fill="currentColor" /> Explore movies</button><button className="secondary-button" onClick={() => { setActiveTab('Series'); setSearchText('') }}>Series</button></div></div>
        <div className="hero-caption">FEATURED CONTENT <span>•</span> {yearOf(featured) || 'DISCOVER'}</div>
      </section>}

      {(activeTab === 'Home' || activeTab === 'Search' || activeTab === 'Movies' || activeTab === 'Series') && <section className="catalogue-section">
        <div className="section-heading"><div><span className="section-kicker">{activeTab === 'Search' ? 'FIND YOUR NEXT WATCH' : activeTab === 'Movies' ? 'MOVIE COLLECTION' : activeTab === 'Series' ? 'SERIES COLLECTION' : 'MOVIES · SERIES · DISCOVERY'}</span><h2>{activeTab === 'Search' ? 'Search catalogue' : activeTab === 'Movies' ? 'Movies' : activeTab === 'Series' ? 'Series' : 'Trending Movies'}</h2></div><button className="text-button" onClick={() => { if (activeTab === 'Home') setActiveTab('Movies'); else void loadCatalogue() }}>{activeTab === 'Home' ? 'See all' : 'Refresh catalogue'} <span>↗</span></button></div>
        {activeTab === 'Search' && <div className="search-tools"><form className="wide-search" onSubmit={doSearch}><Search size={17}/><input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search movies, series, actors…" aria-label="Search movies, series, and actors" autoComplete="off"/><button className="primary-button" type="submit">Search</button></form></div>}
        {watchlistError && <div className="notice error-notice" role="alert"><Info size={18}/><span>{watchlistError}</span></div>}
        {catalogueError && <div className="notice error-notice" role="alert"><Info size={18}/><span>{catalogueError}</span></div>}
        {loading && <div className="loading-line"><LoaderCircle className="spin" size={18} /> Loading titles…</div>}
        {!loading && visibleRows.length === 0 && activeTab === 'Search' && <div className="empty-state"><Search size={27}/><h3>{searchText ? 'No matching titles' : 'What are you in the mood for?'}</h3><p>{searchText ? 'Try another title, actor, or series name.' : 'Start typing and Nexvio will recommend matching movies and series as you type.'}</p></div>}
        {!loading && visibleRows.length === 0 && activeTab !== 'Search' && <div className="empty-state"><Search size={27}/><h3>Catalogue unavailable</h3><p>Check the TMDB API configuration and try refreshing.</p></div>}
        {visibleRows.map(row => <MediaRow key={row.title} title={row.title} items={row.items} onSelect={openTitle} />)}
      </section>}

      {activeTab === 'Live TV' && <section className="workspace-page"><div className="section-heading"><div><span className="section-kicker">USER-SUPPLIED SOURCES</span><h2>Live TV</h2><p>Import an M3U/M3U8 playlist you are authorized to access. Playlist data stays in this browser profile.</p></div></div>
        <div className="iptv-toolbar"><label className="primary-button upload-button"><Upload size={16}/> Import playlist file<input type="file" accept=".m3u,.m3u8,.txt,application/vnd.apple.mpegurl,audio/mpegurl" onChange={async event => { const file = event.target.files?.[0]; if (file) importPlaylistText(await file.text()); event.currentTarget.value = '' }}/></label><form className="playlist-url-form" onSubmit={async event => { event.preventDefault(); const form = event.currentTarget; const input = new FormData(form).get('playlistUrl'); if (typeof input !== 'string' || !input.trim()) return; try { const url = new URL(input.trim()); if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Only HTTP and HTTPS playlist URLs are supported.'); const response = await fetch(url.toString()); if (!response.ok) throw new Error(`Playlist request failed (HTTP ${response.status}).`); importPlaylistText(await response.text()) } catch (error) { setTvError(error instanceof Error ? `${error.message} Some playlist hosts block browser requests through CORS.` : 'Could not load playlist.'); } }}><input name="playlistUrl" type="url" placeholder="https://example.com/playlist.m3u" aria-label="Playlist URL"/><button className="secondary-button" type="submit">Import URL</button></form></div>
        {tvError && <div className="notice error-notice" role="alert"><Info size={18}/><span>{tvError}</span></div>}
        <div className="channel-tools"><input value={tvFilter} onChange={event => setTvFilter(event.target.value)} placeholder="Filter channels or groups" aria-label="Filter channels"/><span>{tvChannels.length} channels</span><button className="text-button" onClick={() => { if (confirm('Remove all imported channels from this browser profile?')) setTvChannels([]) }}><Trash2 size={14}/> Clear playlist</button></div>
        {!tvChannels.length ? <div className="empty-state"><Radio size={28}/><h3>No playlist imported</h3><p>Import an M3U/M3U8 file or a browser-accessible URL from a source you are entitled to use.</p></div> : <div className="channel-grid">{tvChannels.filter(channel => `${channel.name} ${channel.group || ''}`.toLowerCase().includes(tvFilter.toLowerCase())).map(channel => <article className="channel-card" key={channel.id}><button className="channel-main" onClick={() => { setPlayerError(''); setPlayingChannel(channel) }}>{channel.logo ? <img src={channel.logo} alt="" loading="lazy" onError={event => { event.currentTarget.style.display = 'none' }}/> : <Radio size={23}/>}<span><strong>{channel.name}</strong><small>{channel.group || 'Uncategorised'}</small></span><Play size={17}/></button><button className={channel.favorite ? 'favorite-channel active' : 'favorite-channel'} aria-label={channel.favorite ? `Remove ${channel.name} from favorites` : `Add ${channel.name} to favorites`} onClick={() => setTvChannels(current => current.map(item => item.id === channel.id ? {...item, favorite: !item.favorite} : item))}>{channel.favorite ? '★' : '☆'}</button></article>)}</div>}
        <p className="settings-footnote">Browser playback depends on the stream format, codec, CORS, and provider rules. Private playlist URLs are stored locally and are not uploaded to Nexvio cloud sync.</p>
      </section>}

      {activeTab === 'Library' && <section className="workspace-page"><div className="section-heading"><div><span className="section-kicker">PROFILE: {profiles.find(profile => profile.id === activeProfileId)?.name || 'Main Profile'}</span><h2>Your Library</h2><p>Saved titles for this profile. TMDB supplies metadata only; it does not provide playable streams.</p></div></div><div className="sync-status" role="status"><ShieldCheck size={15}/><span>{watchlistError || watchlistStatus}</span></div><div className="library-tabs"><button className="chip active">Watchlist ({watchlist.length})</button><button className="chip" disabled title="Playback history will appear after browser playback is connected">History (not connected)</button></div>{watchlist.length ? <MediaRow title="Saved titles" items={watchlist} onSelect={openTitle}/> : <div className="empty-state"><Bookmark size={28}/><h3>Your library is waiting</h3><p>Add movies or series from their detail pages to keep them here.</p><button className="secondary-button" onClick={() => setActiveTab('Search')}>Discover titles</button></div>}</section>}

      {activeTab === 'Profile' && <section className="workspace-page profile-page"><div className="section-heading"><div><span className="section-kicker">YOUR NEXVIO SPACE</span><h2>Profiles & settings</h2><p>Profiles separate watchlists and preferences within your signed-in account.</p></div></div><div className="profile-list">{profiles.map(profile => <div key={profile.id} className={profile.id === activeProfileId ? 'profile-card active' : 'profile-card'}><button className="profile-select" onClick={() => selectProfile(profile.id)}><span className="profile-avatar">{({'fox':'🦊','bear':'🐻','cat':'🐱','owl':'🦉','tiger':'🐯','panda':'🐼','rabbit':'🐰','frog':'🐸','lion':'🦁','koala':'🐨','pig':'🐷','cow':'🐮','unicorn':'🦄','dragon':'🐲','penguin':'🐧','monkey':'🐵','dog':'🐶','deer':'🦌','butterfly':'🦋','star':'⭐','robot':'🤖','alien':'👽','astronaut':'🧑‍🚀','artist':'🎨'} as Record<string,string>)[profile.avatar || 'fox'] || '🦊'}</span><span><strong>{profile.name}</strong><small>{profile.primary ? 'Primary profile' : 'Profile'}{profile.id === activeProfileId ? ' · Active' : ''}</small></span>{profile.id === activeProfileId && <Check size={18}/>}</button><button className="profile-edit-button" onClick={() => editProfile(profile)} aria-label={`Edit ${profile.name}`}><Settings size={15}/></button></div>)}</div><form className="create-profile-form" onSubmit={saveProfile}><h3>{profileEditingId ? 'Edit profile' : 'Create a profile'}</h3><label htmlFor="profile-name">Profile name</label><div><input id="profile-name" value={profileName} onChange={event => setProfileName(event.target.value)} maxLength={40} required placeholder="e.g. Family"/><select className="settings-select" aria-label="Profile avatar" value={profileAvatar} onChange={event => setProfileAvatar(event.target.value)}>{[['fox','🦊 Fox'],['bear','🐻 Bear'],['cat','🐱 Cat'],['owl','🦉 Owl'],['tiger','🐯 Tiger'],['panda','🐼 Panda'],['rabbit','🐰 Rabbit'],['frog','🐸 Frog'],['lion','🦁 Lion'],['koala','🐨 Koala'],['pig','🐷 Pig'],['cow','🐮 Cow'],['unicorn','🦄 Unicorn'],['dragon','🐲 Dragon'],['penguin','🐧 Penguin'],['monkey','🐵 Monkey'],['dog','🐶 Dog'],['deer','🦌 Deer'],['butterfly','🦋 Butterfly'],['star','⭐ Star'],['robot','🤖 Robot'],['alien','👽 Alien'],['astronaut','🧑‍🚀 Astronaut'],['artist','🎨 Artist']].map(([value,label]) => <option key={value} value={value}>{label}</option>)}</select><button className="primary-button" disabled={profileBusy}>{profileBusy ? 'Saving…' : profileEditingId ? 'Save changes' : 'Create profile'}</button>{profileEditingId && <button type="button" className="secondary-button" onClick={() => { setProfileEditingId(null); setProfileName(''); setProfileAvatar('fox'); setProfileError('') }}>Cancel</button>}</div></form>{profileError && <div className="notice error-notice" role="alert">{profileError}</div>}
        <div className="settings-subheading"><Settings size={18}/><h3>Content & Discovery</h3></div><div className="provider-entry-grid"><button className="settings-card provider-entry" onClick={() => setProviderPage('content')}><div><strong>Content providers</strong><p>Manage add-ons and plugin repositories.</p></div><ChevronDown size={18}/></button><button className="settings-card provider-entry" onClick={() => setProviderPage('addons')}><div><strong>Add-ons</strong><p>Install and manage compatible Stremio add-ons.</p></div><ChevronDown size={18}/></button><button className="settings-card provider-entry" onClick={() => setProviderPage('repositories')}><div><strong>Plugin repositories</strong><p>Register compatible HTTPS provider manifests.</p></div><ChevronDown size={18}/></button></div><div className="settings-subheading"><Settings size={18}/><h3>Preferences</h3><span className="sync-status">{settingsError || settingsStatus}</span></div><div className="settings-card"><div><strong>Accent theme</strong><p>Choose the accent colour used across Nexvio. This preference syncs with your profile.</p></div><select className="settings-select" aria-label="Accent theme" value={webSettings.theme} onChange={event => setWebSettings(value => ({ ...value, theme: event.target.value }))}>{['Emerald', 'White', 'Crimson', 'Ocean', 'Violet', 'Amber', 'Rose'].map(theme => <option key={theme} value={theme}>{theme}</option>)}</select></div><div className="settings-card"><div><strong>AMOLED black</strong><p>Use a true black app background on OLED displays.</p></div><button className={webSettings.amoledBlack ? 'setting-toggle enabled' : 'setting-toggle'} role="switch" aria-label="AMOLED black" aria-checked={webSettings.amoledBlack} onClick={() => setWebSettings(value => ({ ...value, amoledBlack: !value.amoledBlack }))}><span /></button></div><div className="settings-card"><div><strong>Autoplay streams</strong><p>Start compatible imported Live TV streams automatically when the player opens.</p></div><button className={webSettings.playbackAutoplay ? 'setting-toggle enabled' : 'setting-toggle'} role="switch" aria-label="Autoplay streams" aria-checked={webSettings.playbackAutoplay} onClick={() => setWebSettings(value => ({ ...value, playbackAutoplay: !value.playbackAutoplay }))}><span /></button></div><div className="settings-card"><div><strong>Video sizing</strong><p>Controls how the video fits inside the browser player.</p></div><select className="settings-select" aria-label="Video sizing" value={webSettings.videoSizing} onChange={event => setWebSettings(value => ({ ...value, videoSizing: event.target.value }))}>{['Fit', 'Fill', 'Zoom'].map(value => <option key={value}>{value}</option>)}</select></div><div className="settings-card"><div><strong>Preferred audio language</strong><p>Saved as a preference; browser support for selecting audio tracks varies by stream and browser.</p></div><select className="settings-select" aria-label="Preferred audio language" value={webSettings.audioLanguage} onChange={event => setWebSettings(value => ({ ...value, audioLanguage: event.target.value }))}>{['Device language', 'No preference', 'English', 'Japanese', 'Spanish', 'French'].map(value => <option key={value}>{value}</option>)}</select></div><div className="settings-card"><div><strong>Preferred subtitle language</strong><p>Saved as a preference; automatic track selection is not yet connected.</p></div><select className="settings-select" aria-label="Preferred subtitle language" value={webSettings.subtitleLanguage} onChange={event => setWebSettings(value => ({ ...value, subtitleLanguage: event.target.value }))}>{['Device language', 'No subtitles', 'English', 'Japanese', 'Spanish', 'French'].map(value => <option key={value}>{value}</option>)}</select></div><div className="settings-card"><div><strong>Subtitle size</strong><p>Preferred subtitle text size for a compatible player.</p><input className="settings-range" aria-label="Subtitle size" type="range" min="12" max="36" value={webSettings.subtitleSize} onChange={event => setWebSettings(value => ({ ...value, subtitleSize: Number(event.target.value) }))}/><span className="settings-value">{webSettings.subtitleSize}px</span></div></div><div className="settings-card"><div><strong>Reduce motion</strong><p>Disable non-essential interface transitions and animations in this browser.</p></div><button className={webSettings.reduceMotion ? 'setting-toggle enabled' : 'setting-toggle'} role="switch" aria-label="Reduce motion" aria-checked={webSettings.reduceMotion} onClick={() => setWebSettings(value => ({ ...value, reduceMotion: !value.reduceMotion }))}><span /></button></div><button className="secondary-button signout-button" onClick={signOut}><LogOut size={16}/> Sign out</button></section>}
    </main>
    <footer className="footer"><span>© {new Date().getFullYear()} Nexvio</span><span>Movies · Series · Your watchlist</span><span>Playback and cloud data parity are being built in stages.</span></footer>

    {selected && <TitleDetailPage item={selected} saved={watchlist.some(item => item.id === selected.id && kindOf(item) === kindOf(selected))} onSave={() => toggleWatchlist(selected)} onClose={closeTitle} onSearchStreams={(item, season, episode) => setStreamRequest({ item, season, episode })} />}
    {streamRequest && <StreamSearchPage request={streamRequest} onBack={() => setStreamRequest(null)} />}
    {providerPage && <ContentProvidersPage page={providerPage} onNavigate={setProviderPage} onBack={() => setProviderPage(null)} />}
    {playingChannel && <BrowserPlayer channel={playingChannel} error={playerError} setError={setPlayerError} autoplay={webSettings.playbackAutoplay} videoSizing={webSettings.videoSizing} onClose={() => setPlayingChannel(null)} />}
    <MobileNav active={activeTab} onSelect={tab => { setActiveTab(tab); setSearchText(''); setSelected(null) }} />
  </div>
}

function AuthScreen(props: {
  mode: AuthMode; setMode: (mode: AuthMode) => void; email: string; setEmail: (value: string) => void
  password: string; setPassword: (value: string) => void; busy: boolean; error: string; message: string
  setAuthError: (value: string) => void; setAuthMessage: (value: string) => void
  onSubmit: (event: React.FormEvent) => void
}) {
  const { mode, setMode, email, setEmail, password, setPassword, busy, error, message, setAuthError, setAuthMessage, onSubmit } = props
  return <div className="auth-screen">
    <div className="auth-backdrop" />
    <header className="auth-top"><div className="brand"><span className="brand-mark">N</span><span>NEXVIO</span></div><div className="secure-label"><ShieldCheck size={15} /> One account across your devices</div></header>
    <div className="auth-layout"><div className="auth-pitch"><div className="eyebrow"><Sparkles size={14} /> YOUR WORLD OF ENTERTAINMENT</div><h1>Everything you love.<br /><em>One place.</em></h1><p>Discover movies, explore series, and build your personal watchlist with Nexvio.</p><div className="feature-pills"><span><Film size={15} /> Movies</span><span><Tv size={15} /> Series</span><span><Heart size={15} /> Your list</span></div></div>
      <form className="auth-card" onSubmit={onSubmit}><div className="auth-card-mark"><span className="brand-mark">N</span></div><h2>{mode === 'signup' ? 'Create your account' : mode === 'reset' ? 'Reset password' : mode === 'update-password' ? 'Choose a new password' : 'Welcome back'}</h2><p className="auth-subtitle">{mode === 'signup' ? 'Start building your Nexvio experience.' : mode === 'reset' ? 'We’ll send you a password reset link.' : mode === 'update-password' ? 'Choose a new password for your Nexvio account.' : 'Sign in to continue to your Nexvio account.'}</p>
        {supabaseConfigMissing && <div className="notice error-notice"><Info size={17} /><span>Supabase is not configured. Add the web environment variables before using account features.</span></div>}
        {mode !== 'update-password' && <label>Email address<input type="email" autoComplete="email" required value={email} onChange={event => setEmail(event.target.value)} placeholder="you@example.com" /></label>}
        {mode !== 'reset' && <label>{mode === 'update-password' ? 'New password' : 'Password'}<input type="password" autoComplete={mode === 'signin' ? 'current-password' : 'new-password'} required minLength={6} value={password} onChange={event => setPassword(event.target.value)} placeholder="At least 6 characters" /></label>}
        {error && <div className="form-message error">{error}</div>}{message && <div className="form-message success">{message}</div>}
        <button className="primary-button auth-submit" type="submit" disabled={busy || supabaseConfigMissing}>{busy ? <LoaderCircle className="spin" size={18} /> : null}{mode === 'signup' ? 'Create account' : mode === 'reset' ? 'Send reset link' : mode === 'update-password' ? 'Update password' : 'Sign in'}</button>
        <div className="auth-links">{mode === 'signin' && <button type="button" onClick={() => { setAuthMessage(''); setAuthError(''); setMode('reset') }}>Forgot password?</button>}{mode !== 'signin' && mode !== 'update-password' && <button type="button" onClick={() => { setAuthMessage(''); setAuthError(''); setMode('signin') }}>Back to sign in</button>}{mode === 'signin' && <span>New to Nexvio? <button type="button" onClick={() => setMode('signup')}>Create account</button></span>}</div>
        <div className="auth-foot"><ShieldCheck size={14} /> Secured by Supabase Authentication</div>
      </form></div>
  </div>
}

function MediaRow({ title, items, onSelect }: { title: string; items: Title[]; onSelect: (item: Title) => void }) {
  if (!items.length) return null
  return <div className="media-row"><div className="row-title"><h3>{title}</h3><span>{items.length} titles</span></div><div className="poster-strip">{items.map((item, index) => <button className="poster-card" key={`${kindOf(item)}-${item.id}`} onClick={() => onSelect(item)} aria-label={`Open ${titleName(item)}`}><div className="poster-art">{imageUrl(item.poster_path) ? <img src={imageUrl(item.poster_path)} loading={index > 4 ? 'lazy' : 'eager'} alt="" /> : <div className="poster-fallback"><Clapperboard size={28} /></div>}<span className="poster-play"><Play size={20} fill="currentColor" /></span><span className="poster-score">★ {item.vote_average.toFixed(1)}</span></div><div className="poster-caption"><span>{titleName(item)}</span><small>{yearOf(item) || 'New'} · {kindOf(item) === 'tv' ? 'Series' : 'Movie'}</small></div></button>)}</div></div>
}

type TitleDetails = { genres?: {id:number;name:string}[]; runtime?:number; episode_run_time?:number[]; number_of_seasons?:number; seasons?:{season_number:number;name:string;episode_count:number}[] }
type WebAddon = {id:string;name:string;version:string;manifestUrl:string;description:string;resources:string[];types:string[];enabled:boolean}
type WebProvider = {id:string;name:string;file:string;enabled:boolean}
type WebRepo = {id:string;name:string;version:string;manifestUrl:string;description:string;providers:WebProvider[];enabled:boolean}
const ADDONS_KEY='nexvio.web.addons.v1', REPOS_KEY='nexvio.web.repositories.v1'
function readList<T,>(key:string):T[]{try{const x=JSON.parse(localStorage.getItem(key)||'[]');return Array.isArray(x)?x:[]}catch{return[]}}
function TitleDetailPage({item,saved,onSave,onClose,onSearchStreams}:{item:Title;saved:boolean;onSave:()=>void;onClose:()=>void;onSearchStreams:(item:Title,season:number,episode:number)=>void}){
 const [details,setDetails]=useState<TitleDetails|null>(null),[error,setError]=useState(''),[season,setSeason]=useState(1),[episode,setEpisode]=useState(1)
 useEffect(()=>{let live=true;setDetails(null);void tmdb<TitleDetails>('/'+(kindOf(item)==='tv'?'tv':'movie')+'/'+item.id+'?language=en-US').then(d=>{if(live){setDetails(d);setSeason(d.seasons?.find(s=>s.season_number>0)?.season_number||1)}}).catch(e=>{if(live)setError(e instanceof Error?e.message:'Could not load details')});return()=>{live=false}},[item.id,item.media_type])
 useEffect(()=>setEpisode(1),[season])
 const count=details?.seasons?.find(s=>s.season_number===season)?.episode_count||1
 return <div className="detail-page-overlay"><section className="detail-page"><button className="detail-back" onClick={onClose}><ArrowLeft size={18}/> Back</button><div className="detail-hero" style={{backgroundImage:'linear-gradient(0deg,#090b09 0%,rgba(9,11,9,.45) 100%),url("'+(imageUrl(item.backdrop_path,'original')||imageUrl(item.poster_path,'w780'))+'")'}}/><div className="detail-layout"><div className="detail-poster">{imageUrl(item.poster_path,'w500')?<img src={imageUrl(item.poster_path,'w500')} alt={titleName(item)}/>:<Clapperboard size={40}/>}</div><div className="detail-copy"><span className="section-kicker">{kindOf(item)==='tv'?'SERIES':'MOVIE'} · NEXVIO</span><h1>{titleName(item)}</h1><div className="hero-meta"><span className="rating">★ {item.vote_average.toFixed(1)}</span><span>{yearOf(item)}</span>{details?.runtime?<span>{details.runtime} min</span>:null}{details?.number_of_seasons?<span>{details.number_of_seasons} seasons</span>:null}</div>{details?.genres?.length?<div className="genre-list">{details.genres.map(g=><span key={g.id}>{g.name}</span>)}</div>:null}<h3>Overview</h3><p className="detail-overview">{item.overview||'No description is available for this title yet.'}</p>{error&&<p className="details-error">{error}</p>}{kindOf(item)==='tv'&&<div className="episode-picker"><h3>Choose episode</h3><div className="episode-selects"><label>Season<select className="settings-select" value={season} onChange={e=>setSeason(Number(e.target.value))}>{(details?.seasons?.filter(s=>s.season_number>0)||[{season_number:1,name:'Season 1',episode_count:1}]).map(s=><option key={s.season_number} value={s.season_number}>{s.name||('Season '+s.season_number)}</option>)}</select></label><label>Episode<select className="settings-select" value={episode} onChange={e=>setEpisode(Number(e.target.value))}>{Array.from({length:count},(_,i)=>i+1).map(n=><option key={n} value={n}>Episode {n}</option>)}</select></label></div></div>}<div className="detail-actions"><button className="primary-button" onClick={()=>onSearchStreams(item,kindOf(item)==='tv'?season:0,kindOf(item)==='tv'?episode:0)}><Search size={17}/> Search Stream</button><button className="secondary-button" onClick={onSave}>{saved?<Check size={17}/>:<Bookmark size={17}/>} {saved?'Saved to My List':'Add to My List'}</button></div><div className="detail-provider-note"><Info size={16}/> TMDB supplies metadata, not video. Stream search checks compatible installed Stremio add-ons; browser CORS may limit results.</div></div></div></section></div>
}
function ContentProvidersPage({page,onNavigate,onBack}:{page:'content'|'addons'|'repositories';onNavigate:(page:'content'|'addons'|'repositories'|null)=>void;onBack:()=>void}){
 const [addons,setAddons]=useState<WebAddon[]>(()=>readList<WebAddon>(ADDONS_KEY)),[repos,setRepos]=useState<WebRepo[]>(()=>readList<WebRepo>(REPOS_KEY)),[url,setUrl]=useState(''),[busy,setBusy]=useState(false),[message,setMessage]=useState(''),[error,setError]=useState('')
 const saveAddons=(x:WebAddon[])=>{setAddons(x);localStorage.setItem(ADDONS_KEY,JSON.stringify(x))}
 const saveRepos=(x:WebRepo[])=>{setRepos(x);localStorage.setItem(REPOS_KEY,JSON.stringify(x))}
 const install=async(repo:boolean)=>{setBusy(true);setError('');setMessage('');try{const u=new URL(url.trim());if(!['https:','http:'].includes(u.protocol)||u.username||u.password)throw new Error('Enter a valid HTTP(S) manifest URL without credentials.');if(repo&&u.protocol!=='https:')throw new Error('Plugin repository URLs must use HTTPS.');const r=await fetch(u.toString(),{headers:{Accept:'application/json'}});if(!r.ok)throw new Error('Manifest request failed (HTTP '+r.status+').');const raw=await r.text();if(raw.length>1000000)throw new Error('Manifest exceeds 1 MB.');const j:any=JSON.parse(raw);if(!j.name)throw new Error('Manifest is missing its name.');if(repo){const list=Array.isArray(j.providers)?j.providers:Array.isArray(j.scrapers)?j.scrapers:null;if(!list?.length)throw new Error('Repository must include a providers or scrapers list.');saveRepos([{id:u.toString(),name:String(j.name),version:String(j.version||''),manifestUrl:u.toString(),description:String(j.description||''),providers:list.map((p:any,i:number)=>({id:String(p.id||p.filename||p.file||i),name:String(p.name||p.id||p.filename||'Provider'),file:String(p.filename||p.file||''),enabled:p.enabled!==false})),enabled:true},...repos.filter(x=>x.id!==u.toString())]);setMessage('Repository added. Web app currently stores provider metadata; it does not run Android JavaScript plugins.')}else{if(!j.id||!Array.isArray(j.resources)||!Array.isArray(j.types))throw new Error('Expected a compatible Stremio manifest with id, resources, and types.');saveAddons([{id:String(j.id),name:String(j.name),version:String(j.version||'unknown'),manifestUrl:u.toString(),description:String(j.description||''),resources:j.resources.map((r:any)=>typeof r==='string'?r:String(r?.name||r?.id||'')).filter(Boolean),types:j.types.map(String),enabled:true},...addons.filter(x=>x.id!==j.id)]);setMessage('Add-on validated and installed.')}setUrl('')}catch(e){setError(e instanceof Error?e.message:'Could not validate manifest. The host may block browser CORS.')}finally{setBusy(false)}}
 return <div className="provider-page-overlay"><section className="provider-page"><button className="detail-back" onClick={page==='content'?onBack:()=>onNavigate('content')}><ArrowLeft size={18}/> {page==='content'?'Back':'Content providers'}</button>{page==='content'?<><span className="section-kicker">NEXVIO SETTINGS</span><h1>Content providers</h1><p className="provider-lead">Manage installed add-ons or register a plugin repository.</p><button className="provider-choice" onClick={()=>onNavigate('addons')}><span><strong>Add-ons</strong><small>Install and manage compatible Stremio add-ons.</small></span><ChevronDown size={18}/></button><button className="provider-choice" onClick={()=>onNavigate('repositories')}><span><strong>Plugin repositories</strong><small>Add and manage HTTPS repository manifests.</small></span><ChevronDown size={18}/></button></>:page==='addons'?<><span className="section-kicker">CONTENT & DISCOVERY</span><h1>Add-ons</h1><p className="provider-lead">Paste a compatible Stremio add-on JSON manifest URL.</p><form className="provider-install" onSubmit={e=>{e.preventDefault();void install(false)}}><label>Manifest URL</label><input type="url" required value={url} onChange={e=>setUrl(e.target.value)} placeholder="https://provider.example/manifest.json"/><button className="primary-button" disabled={busy}>{busy?'Validating…':'Validate and install'}</button></form>{message&&<div className="notice">{message}</div>}{error&&<div className="notice error-notice">{error}</div>}<h2>Installed providers ({addons.length})</h2>{!addons.length?<div className="empty-state"><Sparkles size={26}/><h3>No add-ons installed yet</h3><p>Add a manifest URL above.</p></div>:addons.map(a=><article className="provider-card" key={a.id}><div className="provider-card-top"><div><h3>{a.name}</h3><small>v{a.version} · {a.enabled?'Enabled':'Disabled'}</small></div><button className={a.enabled?'setting-toggle enabled':'setting-toggle'} onClick={()=>saveAddons(addons.map(x=>x.id===a.id?{...x,enabled:!x.enabled}:x))} aria-label="Toggle add-on"><span/></button></div><p>{a.description}</p><small className="provider-url">{a.manifestUrl}</small><p>Resources: {a.resources.join(', ')}</p><p>Content types: {a.types.join(', ')}</p><button className="secondary-button" onClick={()=>{if(confirm('Remove '+a.name+'?'))saveAddons(addons.filter(x=>x.id!==a.id))}}>Remove</button></article>)}</>:<><span className="section-kicker">CONTENT & DISCOVERY</span><h1>Plugin repositories</h1><p className="provider-lead">Add an HTTPS JSON manifest with a providers or scrapers list.</p><form className="provider-install" onSubmit={e=>{e.preventDefault();void install(true)}}><label>Manifest URL</label><input type="url" required value={url} onChange={e=>setUrl(e.target.value)} placeholder="https://example.com/manifest.json"/><button className="primary-button" disabled={busy}>{busy?'Fetching…':'Add repository'}</button></form>{message&&<div className="notice">{message}</div>}{error&&<div className="notice error-notice">{error}</div>}{!repos.length?<div className="empty-state"><Sparkles size={26}/><h3>No repositories added</h3><p>Add a compatible HTTPS manifest.</p></div>:repos.map(r=><article className="provider-card" key={r.id}><div className="provider-card-top"><h3>{r.name}</h3><button className={r.enabled?'setting-toggle enabled':'setting-toggle'} onClick={()=>saveRepos(repos.map(x=>x.id===r.id?{...x,enabled:!x.enabled}:x))} aria-label="Toggle repository"><span/></button></div><small className="provider-url">{r.manifestUrl}</small><p>{r.providers.length} provider(s) listed</p><div className="repo-provider-list">{r.providers.map(p=><div className="repo-provider-row" key={p.id}><span><strong>{p.name}</strong>{p.file&&<small>{p.file}</small>}</span><button className={p.enabled?'setting-toggle enabled':'setting-toggle'} role="switch" aria-checked={p.enabled} aria-label={'Toggle '+p.name} onClick={()=>saveRepos(repos.map(x=>x.id===r.id?{...x,providers:x.providers.map(y=>y.id===p.id?{...y,enabled:!y.enabled}:y)}:x))}><span/></button></div>)}</div><div className="provider-card-actions"><button className="secondary-button" onClick={()=>{const target=repos.find(x=>x.id===r.id);if(target){setUrl(target.manifestUrl);setMessage('Enter Add repository again to refresh this manifest.')}}}>Use URL</button><button className="secondary-button" onClick={()=>saveRepos(repos.filter(x=>x.id!==r.id))}>Remove repository</button></div></article>)}</>}</section></div>
}
function StreamSearchPage({request,onBack}:{request:{item:Title;season:number;episode:number};onBack:()=>void}){
 const [results,setResults]=useState<{addon:string;title:string;url:string}[]>([]),[busy,setBusy]=useState(true),[errors,setErrors]=useState<string[]>([])
 useEffect(()=>{let live=true;const run=async()=>{setBusy(true);setErrors([]);const installed=readList<WebAddon>(ADDONS_KEY).filter(a=>a.enabled);const wantedType=kindOf(request.item)==='tv'?'series':'movie';if(!installed.length){if(live){setErrors(['No enabled Stremio add-ons are saved in this browser. Open Profile → Content & Discovery → Add-ons and install a manifest URL.']);setBusy(false)}return}
 const normalize=(v:any)=>typeof v==='string'?v.toLowerCase():String(v?.name||v?.id||v?.type||'').toLowerCase();
 const candidates:{addon:WebAddon;resources:string[];types:string[]}[]=[];
 const problems:string[]=[];
 await Promise.all(installed.map(async a=>{try{const r=await fetch(a.manifestUrl,{headers:{Accept:'application/json'}});if(!r.ok)throw new Error('manifest HTTP '+r.status);const m:any=await r.json();const resources=Array.isArray(m.resources)?m.resources.map(normalize):Array.isArray(a.resources)?a.resources.map(normalize):[];const types=Array.isArray(m.types)?m.types.map(normalize):Array.isArray(a.types)?a.types.map(normalize):[];const supportsStream=resources.includes('stream')||resources.includes('streams');const supportsType=types.includes(wantedType)||(wantedType==='series'&&types.includes('tv'));if(supportsStream&&supportsType){candidates.push({addon:a,resources,types});}else{problems.push(a.name+': manifest declares resources ['+resources.join(', ')+'] and types ['+types.join(', ')+']');}}catch(e){const resources=Array.isArray(a.resources)?a.resources.map(normalize):[];const types=Array.isArray(a.types)?a.types.map(normalize):[];if((resources.includes('stream')||resources.includes('streams'))&&(types.includes(wantedType)||(wantedType==='series'&&types.includes('tv'))))candidates.push({addon:a,resources,types});else problems.push(a.name+': could not refresh manifest ('+(e instanceof Error?e.message:'request failed')+'); saved resources ['+resources.join(', ')+'] and types ['+types.join(', ')+']')}}));
 if(!candidates.length){if(live){setErrors(['No enabled add-on manifest currently declares a stream resource for '+wantedType+'. '+(problems.join(' · ')||'Check the installed manifest URLs.')+' If this is a Nuvio JavaScript provider, this browser app cannot execute it.']);setBusy(false)}return}
 const imdb=await tmdb<{imdb_id?:string}>('/'+(kindOf(request.item)==='tv'?'tv':'movie')+'/'+request.item.id+'/external_ids?language=en-US').then(x=>x.imdb_id).catch(()=>undefined);const found:{addon:string;title:string;url:string}[]=[];const seen=new Set<string>();let next=0;
 const searchOne=async(c:typeof candidates[number])=>{const a=c.addon;try{const id=imdb||String(request.item.id);const kind=kindOf(request.item)==='tv'?'series/'+encodeURIComponent(id)+':'+request.season+':'+request.episode:'movie/'+encodeURIComponent(id);const base=a.manifestUrl.replace(/manifest\.json(?:\?.*)?$/i,'').replace(/\/$/,'');const r=await fetch(base+'/stream/'+kind+'.json',{signal:AbortSignal.timeout(18000)});if(!r.ok)throw new Error('HTTP '+r.status);const j:any=await r.json();const streams=Array.isArray(j.streams)?j.streams:[];let accepted=0;for(const stream of streams){let streamUrl='';if(typeof stream.url==='string'&&/^https?:/i.test(stream.url)){streamUrl=stream.url}else if(typeof stream.infoHash==='string'&&/^[a-f0-9]{40}$/i.test(stream.infoHash)){const params=new URLSearchParams();params.set('xt','urn:btih:'+stream.infoHash);if(stream.name||stream.title)params.set('dn',String(stream.name||stream.title));for(const source of Array.isArray(stream.sources)?stream.sources:[])if(typeof source==='string'&&source.startsWith('tracker:'))params.append('tr',source.slice(8));streamUrl='magnet:?'+params.toString()}if(streamUrl&&!seen.has(streamUrl)){seen.add(streamUrl);accepted++;found.push({addon:a.name,title:String(stream.title||stream.name||stream.description||'Stream')+(stream.infoHash&&!stream.url?' · Torrent (open in a compatible external app)':''),url:streamUrl})}}if(!streams.length)problems.push(a.name+': no streams returned for this episode');else if(!accepted)problems.push(a.name+': returned streams, but none had a new supported HTTP URL or torrent info hash')}catch(e){problems.push(a.name+': '+(e instanceof Error?e.message:'request failed')+'; host may block browser CORS')}if(live){setResults([...found]);setErrors([...problems])}};
 const workers=Array.from({length:Math.min(10,candidates.length)},async()=>{while(live){const index=next++;if(index>=candidates.length)return;await searchOne(candidates[index])}});await Promise.all(workers);
 if(live){setResults(found);setErrors(problems);setBusy(false)}};void run();return()=>{live=false}},[request.item.id,request.item.media_type,request.season,request.episode])
 return <div className="provider-page-overlay"><section className="provider-page"><button className="detail-back" onClick={onBack}><ArrowLeft size={18}/> Back to title</button><span className="section-kicker">NEXVIO STREAM SEARCH</span><h1>Search Stream</h1><p className="provider-lead">{titleName(request.item)}{kindOf(request.item)==='tv'?' · Season '+request.season+', Episode '+request.episode:''}</p>{busy&&<div className="loading-line"><LoaderCircle className="spin" size={18}/> Searching installed add-ons…</div>}{!busy&&!results.length&&<div className="empty-state"><Search size={28}/><h3>No streams found</h3><p>Install a compatible add-on or check provider availability.</p></div>}{results.map((r,i)=><article className="provider-card" key={r.url+i}><h3>{r.addon}</h3><p>{r.title}</p><small className="provider-url">{r.url}</small><a className="primary-button stream-open" href={r.url} target="_blank" rel="noreferrer">Open stream</a></article>)}{errors.map((e,i)=><div className="notice error-notice" key={i}>{e}</div>)}<div className="detail-provider-note"><Info size={16}/> Only compatible Stremio add-ons are queried. Nuvio JavaScript providers require a separate runtime and are not executed by this web app.</div></section></div>
}

function BrowserPlayer({ channel, error, setError, autoplay, videoSizing, onClose }: { channel: TvChannel; error: string; setError: (value: string) => void; autoplay: boolean; videoSizing: string; onClose: () => void }) {
  const videoRef = React.useRef<HTMLVideoElement>(null)
  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    const isHls = /\.m3u8(?:$|[?#])/i.test(channel.url)
    let hls: Hls | null = null
    video.removeAttribute('src')
    video.load()
    if (!isHls || video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = channel.url
      setError('')
      return () => { video.pause(); video.removeAttribute('src'); video.load() }
    }
    if (!Hls.isSupported()) {
      setError('This browser cannot play HLS through its available media APIs. Try a supported browser or another compatible stream format.')
      return
    }
    setError('')
    hls = new Hls({ enableWorker: true, lowLatencyMode: true })
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (!data.fatal) return
      if (data.type === Hls.ErrorTypes.NETWORK_ERROR) setError('The HLS playlist or segments could not be loaded. Check the source URL, network access, and the provider’s CORS permissions.')
      else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) setError('The HLS media could not be decoded by this browser. The stream may use an unsupported codec.')
      else setError('HLS playback failed. The source may be offline, restricted, or incompatible with this browser.')
    })
    hls.loadSource(channel.url)
    hls.attachMedia(video)
    return () => { video.pause(); hls?.destroy(); video.removeAttribute('src'); video.load() }
  }, [channel.url, setError])
  return <div className="player-scrim" role="dialog" aria-modal="true" aria-label={`Player: ${channel.name}`}><section className="browser-player"><header><div><strong>{channel.name}</strong><small>Live TV · user-supplied source</small></div><button className="modal-close" onClick={onClose} aria-label="Close player"><X size={20}/></button></header><video ref={videoRef} controls playsInline autoPlay={autoplay} style={{ objectFit: videoSizing === 'Fill' ? 'fill' : videoSizing === 'Zoom' ? 'cover' : 'contain' }} onError={() => setError('Playback failed. The stream may be offline, use an unsupported codec, require headers the browser cannot set, or be blocked by CORS/provider rules.')} onCanPlay={() => setError('')}/>{error && <div className="notice error-notice" role="status"><Info size={18}/><span>{error}</span></div>}<p>Browser playback is limited by codec support, CORS, DRM, and provider authorization. Nexvio does not bypass those restrictions.</p></section></div>
}
function MobileNav({ active, onSelect }: { active: string; onSelect: (tab: string) => void }) {
  const items = [{name:'Home', icon:Clapperboard},{name:'Search',icon:Search},{name:'Live TV',icon:Radio},{name:'Library',icon:Library},{name:'Profile',icon:UserRound}]
  return <nav className="mobile-nav" aria-label="Main navigation">{items.map(item => <button key={item.name} className={active === item.name ? 'active' : ''} onClick={() => onSelect(item.name)} aria-current={active === item.name ? 'page' : undefined}><item.icon size={17}/><span>{item.name}</span></button>)}</nav>
}

createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>)
