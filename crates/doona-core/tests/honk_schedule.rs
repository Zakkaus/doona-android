//! Lane c7-feeds: the refresh schedule over its sidecar record, with the clock passed in.
use doonacore::dto::ErrorCode;
use doonacore::honkconf::feeds::{Authored, IntervalSource, Provider};
use doonacore::honkconf::schedule::{Claims, DEFAULT_HOURS, Schedule};

const HOUR: i64 = 3_600_000;

fn provider(id: &str, interval: Option<(u32, IntervalSource)>) -> Provider {
    Provider {
        id: id.into(),
        name: id.into(),
        url: format!("https://feeds.example.net/{id}"),
        user_agent: None,
        interval: interval.map(|(seconds, source)| Authored { seconds, source }),
    }
}

#[test]
fn the_default_is_on_every_twelve_hours_and_only_the_known_hours_are_taken() {
    let mut schedule = Schedule::default();
    assert_eq!(
        (schedule.enabled, schedule.default_hours),
        (true, DEFAULT_HOURS)
    );
    for hours in [1, 6, 12, 24] {
        schedule.set(false, hours).unwrap();
        assert_eq!((schedule.enabled, schedule.default_hours), (false, hours));
    }
    let before = schedule.clone();
    for hours in [0, 2, 48] {
        assert_eq!(schedule.set(true, hours), Err(ErrorCode::InvalidArgument));
        assert_eq!(schedule, before);
    }
}

#[test]
fn an_early_wake_fetches_nothing_and_two_wakes_fetch_a_due_provider_once() {
    let providers = [provider("s-a", None)];
    let mut schedule = Schedule::default();
    let mut claims = Claims::default();
    let t0 = 1_000_000_000;

    // Never fetched: due now. The second wake meets the fetch still running.
    let first = schedule.refresh_due(&mut claims, &providers, t0);
    assert_eq!(first.claimed, providers);
    let second = schedule.refresh_due(&mut claims, &providers, t0);
    assert!(second.claimed.is_empty());
    assert!(schedule.finish(&mut claims, &providers, "s-a", true, t0));

    // Finished: not due before twelve hours, due at twelve.
    let early = schedule.refresh_due(&mut claims, &providers, t0 + 12 * HOUR - 1);
    assert!(early.claimed.is_empty());
    assert_eq!(early.next_refresh_at, Some(t0 + 12 * HOUR));
    let due = schedule.refresh_due(&mut claims, &providers, t0 + 12 * HOUR);
    assert_eq!(due.claimed, providers);
    assert_eq!(due.next_refresh_at, None);
}

#[test]
fn the_feed_interval_decides_and_reports_its_source() {
    let mut schedule = Schedule::default();
    schedule.set(true, 24).unwrap();
    let own = provider("s-own", Some((3_600, IntervalSource::Subscription)));
    let assets = provider("s-assets", Some((21_600, IntervalSource::Assets)));
    let global = provider("s-global", None);
    let seen: Vec<_> = [&own, &assets, &global]
        .map(|p| {
            let f = schedule.feed(p);
            (f.effective_interval_seconds, f.interval_source)
        })
        .into();
    assert_eq!(
        seen,
        [
            (3_600, IntervalSource::Subscription),
            (21_600, IntervalSource::Assets),
            (86_400, IntervalSource::Global),
        ]
    );
}

#[test]
fn a_turned_on_default_applies_to_unconfigured_feeds_only() {
    let providers = [
        provider("s-own", Some((6 * 3_600, IntervalSource::Subscription))),
        provider("s-none", None),
    ];
    let mut schedule = Schedule::default();
    schedule.set(true, 24).unwrap();
    let mut claims = Claims::default();
    let t0 = 5_000_000_000;
    let first = schedule.refresh_due(&mut claims, &providers, t0);
    assert_eq!(first.claimed.len(), 2);
    for id in ["s-own", "s-none"] {
        schedule.finish(&mut claims, &providers, id, true, t0);
    }
    let at_six = schedule.refresh_due(&mut claims, &providers, t0 + 6 * HOUR);
    let ids: Vec<_> = at_six.claimed.iter().map(|p| p.id.as_str()).collect();
    assert_eq!(ids, ["s-own"]);
    assert_eq!(at_six.next_refresh_at, Some(t0 + 24 * HOUR));
}

#[test]
fn off_stops_scheduled_fetches_but_a_manual_update_still_runs() {
    let providers = [provider(
        "s-a",
        Some((6 * 3_600, IntervalSource::Subscription)),
    )];
    let mut schedule = Schedule::default();
    schedule.set(false, 12).unwrap();
    let mut claims = Claims::default();
    let t0 = 7_000_000_000;
    let due = schedule.refresh_due(&mut claims, &providers, t0 + 100 * HOUR);
    assert!(due.claimed.is_empty());
    assert_eq!(due.next_refresh_at, None);
    assert_eq!(
        schedule.overview(&claims, &providers, t0).next_refresh_at,
        None
    );

    assert!(claims.claim(&providers[0]));
    assert!(
        !claims.claim(&providers[0]),
        "a second manual update joins the first"
    );
    assert!(schedule.finish(&mut claims, &providers, "s-a", true, t0));

    schedule.set(true, 12).unwrap();
    let overview = schedule.overview(&claims, &providers, t0);
    assert_eq!(overview.next_refresh_at, Some(t0 + 6 * HOUR));
}

#[test]
fn a_manual_interval_is_never_scheduled() {
    let providers = [provider("s-m", Some((0, IntervalSource::Subscription)))];
    let schedule = Schedule::default();
    let mut claims = Claims::default();
    let due = schedule.refresh_due(&mut claims, &providers, 1);
    assert!(due.claimed.is_empty());
    assert_eq!(due.next_refresh_at, None);
    assert_eq!(schedule.feed(&providers[0]).effective_interval_seconds, 0);
}

#[test]
fn a_failed_fetch_waits_an_hour_not_a_whole_interval() {
    let providers = [provider("s-a", None)];
    let mut schedule = Schedule::default();
    let mut claims = Claims::default();
    let t0 = 9_000_000_000;
    schedule.refresh_due(&mut claims, &providers, t0);
    schedule.finish(&mut claims, &providers, "s-a", false, t0);
    assert!(
        schedule
            .refresh_due(&mut claims, &providers, t0 + HOUR - 1)
            .claimed
            .is_empty()
    );
    assert_eq!(
        schedule
            .refresh_due(&mut claims, &providers, t0 + HOUR)
            .claimed
            .len(),
        1
    );
}

#[test]
fn a_late_result_for_a_removed_or_changed_provider_is_dropped() {
    let original = provider("s-a", None);
    let mut schedule = Schedule::default();
    let t0 = 11_000_000_000;

    // Removed while the fetch ran.
    let mut claims = Claims::default();
    schedule.refresh_due(&mut claims, std::slice::from_ref(&original), t0);
    assert!(!schedule.finish(&mut claims, &[], "s-a", true, t0 + 1));

    // The User-Agent changed while the fetch ran.
    let mut edited = original.clone();
    edited.user_agent = Some("custom/2".into());
    schedule.refresh_due(&mut claims, std::slice::from_ref(&original), t0);
    assert!(!schedule.finish(
        &mut claims,
        std::slice::from_ref(&edited),
        "s-a",
        true,
        t0 + 1
    ));

    // Neither result was recorded: the provider is still due at once.
    let again = schedule.refresh_due(&mut claims, std::slice::from_ref(&edited), t0 + 2);
    assert_eq!(again.claimed, [edited]);
}

#[test]
fn a_refused_claim_does_not_replace_the_fetch_in_flight() {
    let original = provider("s-a", None);
    let mut edited = original.clone();
    edited.user_agent = Some("custom/2".into());
    let mut schedule = Schedule::default();
    let mut claims = Claims::default();
    assert!(claims.claim(&original));
    assert!(
        !claims.claim(&edited),
        "a manual update after the edit joins the fetch"
    );
    // The body fetched with the old User-Agent is not recorded under the new one.
    assert!(!schedule.finish(&mut claims, std::slice::from_ref(&edited), "s-a", true, 1));
}

#[test]
fn a_changed_user_agent_does_not_inherit_the_old_progress() {
    let old = provider("s-a", None);
    let mut edited = old.clone();
    edited.user_agent = Some("custom/2".into());
    let mut schedule = Schedule::default();
    let mut claims = Claims::default();
    let t0 = 20_000_000_000;
    schedule.refresh_due(&mut claims, std::slice::from_ref(&old), t0);
    assert!(schedule.finish(&mut claims, std::slice::from_ref(&old), "s-a", true, t0));

    let due = schedule.refresh_due(&mut claims, std::slice::from_ref(&edited), t0 + 1);
    assert_eq!(due.claimed, [edited.clone()]);
    assert!(schedule.finish(
        &mut claims,
        std::slice::from_ref(&edited),
        "s-a",
        false,
        t0 + 2
    ));
    let again = schedule.refresh_due(&mut claims, std::slice::from_ref(&edited), t0 + 3);
    assert!(
        again.claimed.is_empty(),
        "the new User-Agent's own failure is remembered"
    );
}

#[test]
fn the_record_round_trips_as_camel_case_json() {
    let mut schedule = Schedule::default();
    let providers = [provider("s-a", None)];
    let mut claims = Claims::default();
    schedule.refresh_due(&mut claims, &providers, 42);
    schedule.finish(&mut claims, &providers, "s-a", true, 42);
    let text = serde_json::to_string(&schedule).unwrap();
    assert_eq!(
        text,
        r#"{"enabled":true,"defaultHours":12,"progress":{"s-a":{"userAgent":null,"lastSuccessMs":42,"lastFailureMs":null}}}"#
    );
    assert_eq!(serde_json::from_str::<Schedule>(&text).unwrap(), schedule);
}
