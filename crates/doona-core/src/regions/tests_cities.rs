use super::*;
use std::collections::HashSet;

#[test]
fn catalog_ids_names_and_aliases_select_their_city() {
    let mut ids = HashSet::new();
    for site in cities::SITES {
        assert!(ids.insert(site.id));
        assert!(site.id.bytes().all(|b| b == b'-' || b.is_ascii_lowercase()));
        assert!(site.point.lat.abs() <= 90.0 && site.point.lon.abs() <= 180.0);
        for raw in site.keys() {
            let key = raw.trim_start_matches(['?', '^']);
            let input = if raw.starts_with('?') {
                format!("{} {key}-02", site.iso)
            } else {
                key.to_owned()
            };
            let result = locate(&input);
            assert_eq!(result.region.as_deref(), Some(site.iso), "{input}");
            let city = result
                .city
                .unwrap_or_else(|| panic!("missing {} for {input}", site.id));
            assert_eq!(city.id, site.id, "{input}");
            assert_eq!(city.coordinates, site.point, "{input}");
            let names = city.names.unwrap();
            assert_eq!(
                [
                    names.en.as_ref(),
                    names.zh_tw.as_ref(),
                    names.zh_cn.as_ref()
                ],
                site.names
            );
            assert!(
                site.names[1..]
                    .iter()
                    .all(|s| s.chars().any(|c| ('\u{3400}'..='\u{9fff}').contains(&c)))
            );
        }
    }
}

#[test]
fn ambiguous_codes_need_evidence_and_never_match_inside_words() {
    for a in MATCHER.aliases.iter().filter(|a| a.risky || a.short_code) {
        for text in [
            format!("note {} today", a.key),
            format!("x{}01", a.key),
            format!("{}01x", a.key),
            format!("{}xyz-01", a.key),
            format!("{}\u{301}1", a.key),
        ] {
            assert!(locate(&text).city.is_none(), "{text}");
        }
        for suffix in ["01", "-2", " 03"] {
            let text = format!("{} {}{suffix}", a.iso, a.key);
            assert_eq!(locate(&text).city.unwrap().id, a.city.unwrap().id, "{text}");
        }
    }
    for text in [
        "la ruta sin salida",
        "ny version",
        "she can see the sea",
        "playstation",
        "sjcville",
        "東京灣",
        "深夜",
        "廣告",
        "山東京都",
    ] {
        assert!(locate(text).city.is_none(), "{text}");
    }
    for (name, id) in [
        ("la01", "los-angeles"),
        ("sin-2", "singapore"),
        ("NY", "new-york"),
        ("sjc-2", "san-jose"),
        ("Kwai Chung", "hong-kong"),
        ("將軍澳", "hong-kong"),
        ("Jurong West", "singapore"),
        ("DÜSSELDORF", "dusseldorf"),
    ] {
        assert_eq!(locate(name).city.unwrap().id, id, "{name}");
    }
}

#[test]
fn relay_labels_describe_only_the_exit() {
    for (route, iso, id) in [
        ("沪日", "JP", None),
        ("滬日", "JP", None),
        ("广港", "HK", Some("hong-kong")),
        ("廣港", "HK", Some("hong-kong")),
        ("深港", "HK", Some("hong-kong")),
        ("京德", "DE", None),
        ("蓉新", "SG", Some("singapore")),
        ("廣州日本", "JP", None),
    ] {
        for suffix in ["", "01", " IPLC", "-02 IEPL", "BGP3", "专线", "專線 5"] {
            let name = format!("{route}{suffix}");
            let result = locate(&name);
            assert_eq!(result.region.as_deref(), Some(iso), "{name}");
            assert_eq!(result.city.map(|c| c.id).as_deref(), id, "{name}");
        }
    }
    for (name, id) in [
        ("沪日-大阪", "osaka"),
        ("IPLC京德 Frankfurt", "frankfurt"),
        ("01深港 IEPL", "hong-kong"),
    ] {
        assert_eq!(locate(name).city.unwrap().id, id, "{name}");
    }
    assert_eq!(locate("东京日本").region.as_deref(), Some("JP"));
    assert_eq!(locate("高深港湾").city, None);
}
