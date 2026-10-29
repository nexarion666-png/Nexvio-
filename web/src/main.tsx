import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import type { Session } from '@supabase/supabase-js'
import {
  ArrowLeft, Bookmark, Check, ChevronDown, CircleUserRound, Clapperboard,
  Film, Heart, Info, LoaderCircle, LogOut, Play, Search, ShieldCheck,
  Sparkles, Tv, X,
} from 'lucide-react'
import { supabase, supabaseConfigMissing } from './lib/supabase'
import { loadCloudWatchlist, saveCloudWatchlist } from './lib/watchlistSync'
import { loadCloudSettings, saveCloudSettings } from './lib/settingsSync'
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
  const [query, setQuery] = useState('')
  const [searchText, setSearchText] = useState('')
  const [featured, setFeatured] = useState<Title | null>(null)
  const [selected, setSelected] = useState<Title | null>(null)
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
  const [webSettings, setWebSettings] = useState({ autoplayPreviews: false, rememberProgress: true })

  useEffect(() => {
    if (!supabase) { setAuthReady(true); return }
    const { data } = supabase.auth.onAuthStateChange((event, nextSession) => {
      setSession(nextSession)
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
    void loadCloudWatchlist(supabase, accountId).then(items => {
      if (cancelled) return
      setWatchlist(items as Title[])
      setWatchlistReadyFor(accountId)
      setWatchlistStatus('Cloud list synced')
    }).catch((error: unknown) => {
      if (cancelled) return
      setWatchlistError(error instanceof Error ? error.message : 'Could not load your cloud list.')
      setWatchlistStatus('Cloud sync failed')
    })
    return () => { cancelled = true }
  }, [session?.user.id])

  useEffect(() => {
    const accountId = session?.user.id
    if (!accountId || !supabase || watchlistReadyFor !== accountId) return
    let cancelled = false
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const { data: current } = await supabase!.auth.getSession()
          if (cancelled || current.session?.user.id !== accountId) return
          setWatchlistStatus('Saving cloud list…')
          setWatchlistError('')
          await saveCloudWatchlist(supabase!, accountId, watchlist)
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
  }, [watchlist, session?.user.id, watchlistReadyFor])

  useEffect(() => {
    const accountId = session?.user.id
    let cancelled = false
    setSettingsReadyFor(null)
    setSettingsError('')
    if (!accountId || !supabase) return () => { cancelled = true }
    setSettingsStatus('Loading preferences…')
    void loadCloudSettings(supabase, accountId).then(remote => {
      if (cancelled) return
      setWebSettings({
        autoplayPreviews: remote.web_autoplay_previews === true,
        rememberProgress: remote.web_remember_progress !== false,
      })
      setSettingsReadyFor(accountId)
      setSettingsStatus('Preferences synced')
    }).catch((error: unknown) => {
      if (cancelled) return
      setSettingsError(error instanceof Error ? error.message : 'Could not load preferences.')
      setSettingsStatus('Preferences sync failed')
    })
    return () => { cancelled = true }
  }, [session?.user.id])

  useEffect(() => {
    const accountId = session?.user.id
    if (!accountId || !supabase || settingsReadyFor !== accountId) return
    let cancelled = false
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const { data: before } = await supabase!.auth.getSession()
          if (cancelled || before.session?.user.id !== accountId) return
          setSettingsStatus('Saving preferences…')
          setSettingsError('')
          await saveCloudSettings(supabase!, accountId, {
            web_autoplay_previews: webSettings.autoplayPreviews,
            web_remember_progress: webSettings.rememberProgress,
          })
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
  }, [webSettings, session?.user.id, settingsReadyFor])

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

  const toggleWatchlist = (item: Title) => {
    setWatchlist(current => current.some(saved => saved.id === item.id && kindOf(saved) === kindOf(item))
      ? current.filter(saved => !(saved.id === item.id && kindOf(saved) === kindOf(item)))
      : [...current, item])
  }

  const doSearch = async (event: React.FormEvent) => {
    event.preventDefault()
    const value = query.trim()
    if (!value) { setSearchText(''); return }
    setLoading(true); setCatalogueError(''); setSearchText(value); setActiveTab('Search')
    try {
      const result = await tmdb<{ results: Title[] }>(`/search/multi?language=en-US&include_adult=false&query=${encodeURIComponent(value)}`)
      setRows([{ title: `Results for “${value}”`, items: result.results.filter(item => (item.media_type as string | undefined) !== 'person' && item.poster_path) }])
    } catch (error) {
      setCatalogueError(error instanceof Error ? error.message : 'Search failed.')
    } finally { setLoading(false) }
  }

  const visibleRows = useMemo(() => {
    if (activeTab === 'Movies') return rows.filter(row => row.title.includes('Movies') || row.title.includes('Trending'))
    if (activeTab === 'Series') return rows.filter(row => row.title.includes('Series') || row.title.includes('Trending'))
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
      <button className="brand" onClick={() => { setActiveTab('Home'); setSearchText('') }} aria-label="Nexvio home"><span className="brand-mark">N</span><span>NEXVIO</span></button>
      <nav className="desktop-nav" aria-label="Main navigation">
        {['Home', 'Movies', 'Series', 'My List', 'Settings'].map(tab => <button key={tab} className={activeTab === tab ? 'nav-item active' : 'nav-item'} onClick={() => { setActiveTab(tab); setSearchText('') }}>{tab}</button>)}
      </nav>
      <form className="search-box" onSubmit={doSearch}><Search size={17} /><input value={query} onChange={event => setQuery(event.target.value)} placeholder="Titles, genres" aria-label="Search titles" /><button type="submit" aria-label="Submit search"><ArrowLeft className="search-enter" size={16} /></button></form>
      <div className="account-wrap">
        <button className="account-button" onClick={() => setShowAccount(value => !value)} aria-label="Account menu"><CircleUserRound size={21} /><ChevronDown size={14} /></button>
        {showAccount && <div className="account-menu"><div className="account-email">{session.user.email}</div><div className="account-note"><ShieldCheck size={14} /> Same Nexvio Supabase account</div><button onClick={() => { setActiveTab('Settings'); setShowAccount(false) }}><ShieldCheck size={15} /> Preferences</button><button onClick={signOut}><LogOut size={15} /> Sign out</button></div>}
      </div>
    </header>

    <main>
      {activeTab === 'Home' && !searchText && featured && <section className="hero" style={{ backgroundImage: `linear-gradient(90deg, #080b0a 0%, rgba(8,11,10,.91) 28%, rgba(8,11,10,.24) 68%, rgba(8,11,10,.15) 100%), linear-gradient(0deg, #080b0a 0%, transparent 58%), url("${imageUrl(featured.backdrop_path, 'original')}")` }}>
        <div className="hero-content"><div className="eyebrow"><Sparkles size={14} /> YOUR NEXT OBSESSION</div><h1>{titleName(featured)}</h1><div className="hero-meta"><span className="rating">★ {featured.vote_average.toFixed(1)}</span><span>{yearOf(featured)}</span><span className="tag">HD</span><span>{kindOf(featured) === 'tv' ? 'Series' : 'Movie'}</span></div><p>{featured.overview || 'Discover something new to watch.'}</p><div className="hero-actions"><button className="primary-button" onClick={() => setSelected(featured)}><Play size={18} fill="currentColor" /> Explore title</button><button className="secondary-button" onClick={() => toggleWatchlist(featured)}>{watchlist.some(item => item.id === featured.id) ? <Check size={17} /> : <Bookmark size={17} />} My List</button></div></div>
        <div className="hero-caption">FEATURED TONIGHT <span>•</span> {yearOf(featured) || 'NOW SHOWING'}</div>
      </section>}

      {activeTab !== 'Settings' && <section className="catalogue-section">
        <div className="section-heading"><div><span className="section-kicker">{activeTab === 'My List' ? 'SAVED FOR LATER' : activeTab === 'Search' ? 'FIND YOUR NEXT WATCH' : 'HANDPICKED FOR YOU'}</span><h2>{activeTab === 'Search' ? 'Search' : activeTab === 'My List' ? 'My List' : activeTab === 'Movies' ? 'Movies' : activeTab === 'Series' ? 'Series' : 'Explore Nexvio'}</h2></div><button className="text-button" onClick={() => { setActiveTab('Home'); setSearchText(''); void loadCatalogue() }}>Refresh catalogue <span>↗</span></button></div>
        {session && <div className={watchlistError ? 'notice error-notice' : 'sync-status'} role="status"><ShieldCheck size={15} /><span>{watchlistError || watchlistStatus}</span></div>}
        {catalogueError && <div className="notice error-notice"><Info size={18} /><span>{catalogueError}</span></div>}
        {loading && <div className="loading-line"><LoaderCircle className="spin" size={18} /> Loading titles…</div>}
        {!loading && visibleRows.length === 0 && <div className="empty-state"><Bookmark size={27} /><h3>{activeTab === 'My List' ? 'Your list is empty' : 'No titles found'}</h3><p>{activeTab === 'My List' ? 'Save movies and series to keep them close.' : 'Try a different search or refresh the catalogue.'}</p></div>}
        {visibleRows.map(row => <MediaRow key={row.title} title={row.title} items={row.items} onSelect={setSelected} />)}
      </section>}
      {activeTab === 'Settings' && <section className="settings-page"><div className="section-heading"><div><span className="section-kicker">YOUR NEXVIO ACCOUNT</span><h2>Preferences</h2></div></div><div className={settingsError ? 'notice error-notice' : 'sync-status'} role="status"><ShieldCheck size={15} /><span>{settingsError || settingsStatus}</span></div><div className="settings-card"><div><strong>Autoplay previews</strong><p>Automatically start previews when preview playback becomes available.</p></div><button className={webSettings.autoplayPreviews ? 'setting-toggle enabled' : 'setting-toggle'} role="switch" aria-checked={webSettings.autoplayPreviews} onClick={() => setWebSettings(value => ({ ...value, autoplayPreviews: !value.autoplayPreviews }))}><span /></button></div><div className="settings-card"><div><strong>Remember playback progress</strong><p>Keep playback position across sessions when video playback is connected.</p></div><button className={webSettings.rememberProgress ? 'setting-toggle enabled' : 'setting-toggle'} role="switch" aria-checked={webSettings.rememberProgress} onClick={() => setWebSettings(value => ({ ...value, rememberProgress: !value.rememberProgress }))}><span /></button></div><p className="settings-footnote">These preferences sync to your Nexvio account. Playback behavior takes effect when browser playback is implemented.</p></section>}
    </main>
    <footer className="footer"><span>© {new Date().getFullYear()} Nexvio</span><span>Movies · Series · Your watchlist</span><span>Playback and cloud data parity are being built in stages.</span></footer>

    {selected && <TitleModal item={selected} saved={watchlist.some(item => item.id === selected.id && kindOf(item) === kindOf(selected))} onSave={() => toggleWatchlist(selected)} onClose={() => setSelected(null)} />}
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

function TitleModal({ item, saved, onSave, onClose }: { item: Title; saved: boolean; onSave: () => void; onClose: () => void }) {
  useEffect(() => { const handle = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }; window.addEventListener('keydown', handle); return () => window.removeEventListener('keydown', handle) }, [onClose])
  return <div className="modal-scrim" onMouseDown={event => { if (event.target === event.currentTarget) onClose() }}><section className="title-modal"><button className="modal-close" onClick={onClose} aria-label="Close"><X size={20} /></button><div className="modal-backdrop" style={{ backgroundImage: `linear-gradient(0deg, #111512 0%, transparent 100%), url("${imageUrl(item.backdrop_path, 'original') || imageUrl(item.poster_path, 'w780')}")` }} /><div className="modal-body"><span className="section-kicker">{kindOf(item) === 'tv' ? 'SERIES' : 'MOVIE'} · NEXVIO</span><h2>{titleName(item)}</h2><div className="hero-meta"><span className="rating">★ {item.vote_average.toFixed(1)}</span><span>{yearOf(item)}</span><span className="tag">TMDB</span></div><p>{item.overview || 'No description is available for this title yet.'}</p><div className="hero-actions"><button className="primary-button" onClick={() => alert('Provider playback is the next integration milestone. This title page is connected to the live catalogue.')}><Play size={17} fill="currentColor" /> Find to watch</button><button className="secondary-button" onClick={onSave}>{saved ? <Check size={17} /> : <Bookmark size={17} />}{saved ? 'Saved to My List' : 'Add to My List'}</button></div><div className="modal-note"><Info size={15} /> Stream availability depends on your installed, supported provider sources.</div></div></section></div>
}

createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>)
