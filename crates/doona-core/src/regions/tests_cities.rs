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
            let key = raw.trim_start_matches(['?', '^', '+']);
            let input = if raw.starts_with(['?', '+']) {
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
    for a in MATCHER
        .aliases
        .iter()
        .filter(|a| a.code != CodeContext::Free)
    {
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
            let expected = !(a.code == CodeContext::Numbered && suffix.starts_with(' '));
            assert_eq!(
                locate(&text).city.map(|s| s.id).as_deref(),
                expected.then(|| a.city.unwrap().id),
                "{text}"
            );
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

#[test]
fn provider_location_examples() {
    for (name, iso, city) in [
        ("日本东京01", "JP", Some("tokyo")),
        ("JP埼玉01", "JP", Some("saitama")),
        ("美国洛杉矶01", "US", Some("los-angeles")),
        ("韩国首尔01", "KR", Some("seoul")),
        ("德国法兰克福", "DE", Some("frankfurt")),
        ("台湾新北01", "TW", Some("new-taipei")),
        ("香港IPLC", "HK", Some("hong-kong")),
        ("東京灣", "JP", None),
        ("山東京都", "JP", None),
        ("上海-日本 IPLC", "JP", None),
        ("上海→日本 01", "JP", None),
        ("沪-日 01", "JP", None),
        ("深圳-香港", "HK", Some("hong-kong")),
        ("广州 香港 IEPL", "HK", Some("hong-kong")),
        ("北京 / Vancouver", "CA", Some("vancouver")),
        ("上海港", "CN", Some("shanghai")),
        ("广州港 01", "CN", Some("guangzhou")),
        ("深港专线", "HK", Some("hong-kong")),
        ("Nodo de prueba", "DE", None),
        ("You can use this", "CA", None),
        ("Contact us", "US", None),
    ] {
        let result = locate(name);
        assert_eq!(
            (
                result.region.as_deref(),
                result.city.map(|c| c.id).as_deref()
            ),
            (Some(iso), city),
            "{name}"
        );
    }
}

#[test]
fn numbered_airport_codes_are_not_words() {
    for (code, city) in [
        ("inc", "yinchuan"),
        ("otp", "bucharest"),
        ("sel", "seoul"),
        ("chi", "chicago"),
        ("lin", "milan"),
        ("saw", "istanbul"),
        ("beg", "belgrade"),
        ("lad", "luanda"),
    ] {
        assert_eq!(locate(code), Location::default(), "{code}");
        assert_eq!(locate(&code.to_uppercase()), Location::default(), "{code}");
        assert_eq!(locate(&format!("{code}-2")).city.unwrap().id, city);
    }
    for label in ["ACME INC", "OTP 01", "TAI CHI", "MRS LIN"] {
        assert_eq!(locate(label), Location::default(), "{label}");
    }
}

#[test]
fn city_centers_in_tenths_of_a_degree() {
    for (name, lat, lon) in [
        ("Sydney", -339, 1512),
        ("São Paulo", -236, -466),
        ("Reykjavík", 642, -219),
        ("Honolulu", 213, -1579),
        ("Auckland", -369, 1748),
        ("Lisbon", 387, -91),
        ("Accra", 56, -2),
        ("Vancouver", 493, -1231),
        ("Johannesburg", -262, 281),
        ("Buenos Aires", -346, -584),
        ("The Dalles", 456, -1212),
        ("Hamina", 606, 272),
        ("Ulanchab", 410, 1131),
        ("Saint John", 453, -661),
        ("Kamloops", 507, -1203),
    ] {
        let p = locate(name).city.unwrap().coordinates;
        assert_eq!(
            ((p.lat * 10.0).round() as i32, (p.lon * 10.0).round() as i32),
            (lat, lon),
            "{name}"
        );
    }
}
