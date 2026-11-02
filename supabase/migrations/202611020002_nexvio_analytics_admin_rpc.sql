-- Private, aggregate-only analytics access for the Nexvio administrator.
-- The underlying event table remains unavailable for client-side SELECT queries.
CREATE OR REPLACE FUNCTION public.get_nexvio_analytics_dashboard()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  result jsonb;
BEGIN
  IF auth.uid() IS NULL
     OR lower(coalesce(auth.jwt() ->> 'email', '')) <> 'clintonawise333@gmail.com' THEN
    RAISE EXCEPTION 'Not authorized to view Nexvio analytics'
      USING ERRCODE = '42501';
  END IF;

  WITH event_totals AS (
    SELECT
      count(*)::bigint AS total_app_opens,
      count(DISTINCT installation_id)::bigint AS total_installations,
      count(DISTINCT installation_id) FILTER (
        WHERE received_at >= now() - interval '24 hours'
      )::bigint AS active_installations_24h,
      count(DISTINCT installation_id) FILTER (
        WHERE received_at >= now() - interval '7 days'
      )::bigint AS active_installations_7d,
      count(DISTINCT installation_id) FILTER (
        WHERE received_at >= now() - interval '30 days'
      )::bigint AS active_installations_30d
    FROM public.nexvio_analytics_events
  ),
  daily AS (
    SELECT
      d.day::date AS day,
      count(e.id)::bigint AS app_opens,
      count(DISTINCT e.installation_id)::bigint AS installations
    FROM generate_series(
      current_date - 29,
      current_date,
      interval '1 day'
    ) AS d(day)
    LEFT JOIN public.nexvio_analytics_events AS e
      ON e.received_at >= d.day
     AND e.received_at < d.day + interval '1 day'
    GROUP BY d.day
    ORDER BY d.day
  ),
  versions AS (
    SELECT
      coalesce(nullif(app_version, ''), 'Unknown') AS version,
      count(*)::bigint AS app_opens,
      count(DISTINCT installation_id)::bigint AS installations
    FROM public.nexvio_analytics_events
    GROUP BY coalesce(nullif(app_version, ''), 'Unknown')
    ORDER BY count(*) DESC
  )
  SELECT jsonb_build_object(
    'total_app_opens', t.total_app_opens,
    'total_installations', t.total_installations,
    'active_installations_24h', t.active_installations_24h,
    'active_installations_7d', t.active_installations_7d,
    'active_installations_30d', t.active_installations_30d,
    'daily_activity', coalesce(
      (SELECT jsonb_agg(jsonb_build_object(
        'day', day, 'app_opens', app_opens, 'installations', installations
      ) ORDER BY day) FROM daily), '[]'::jsonb
    ),
    'app_versions', coalesce(
      (SELECT jsonb_agg(jsonb_build_object(
        'version', version, 'app_opens', app_opens, 'installations', installations
      ) ORDER BY app_opens DESC) FROM versions), '[]'::jsonb
    ),
    'generated_at', now()
  )
  INTO result
  FROM event_totals AS t;

  RETURN result;
END;
$$;

REVOKE ALL ON FUNCTION public.get_nexvio_analytics_dashboard() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.get_nexvio_analytics_dashboard() FROM anon;
GRANT EXECUTE ON FUNCTION public.get_nexvio_analytics_dashboard() TO authenticated;
