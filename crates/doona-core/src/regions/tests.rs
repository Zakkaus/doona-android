use super::*;
use std::collections::HashMap;

#[test]
fn web_region_and_country_flag_examples() {
    // geo.test.ts and countryFlags.test.ts: display overrides, decoration and picker sorting
    // belong to the web UI; embedded flags here return the region, not a second decoration.
    for (iso, cases) in [
        (
            "HK",
            "HK-01|港 01|🇭🇰-家宽-倍率|🇭🇰🇺🇸|🇭🇰 JP|Hong Kong China|香港 01|HK-03|🇭🇰 HK 01|香港-HK|Hong Kong HK|hongkong|hk-01|hk-02",
        ),
        ("TW", "🇹🇼|Taiwan|台湾|台灣|臺灣"),
        (
            "JP",
            "JP-01|Japan Tokyo|日本高速|JPN|🇽🇽 🇯🇵|JP 🇦🇶|JP 🇭🇰|Node 7 🇯🇵 Tokyo-7 IPLC x0|𝐉apan Tokyo|Japan 🇯🇵|JP memoized|🇯🇵 memoized|JP oldest cache entry|Japan|🇯🇵 Japan|Japan resolved memoized|🇯🇵 resolved memoized|jp-01",
        ),
        (
            "US",
            "San Jose premium|United States of America|🇺🇸 LA 01|US-CL 01|us_02|USA 03|United States 04|美国专线|美國專線|🇺🇸|美国 洛杉矶 01 家宽|usa-us|🇺🇸 Japan|us-01",
        ),
        ("KR", "Kr seoul"),
        ("CA", "CA-01"),
        ("MO", "Macau|Macao|澳门节点|澳門節點"),
        (
            "SG",
            "best node in Singapore|Singapore 01|新加坡|SGP|ſg|ſingapore|sg-01",
        ),
        (
            "GB",
            "UK London|UK london|🏴\u{e0067}\u{e0062}\u{e0065}\u{e006e}\u{e0067}\u{e007f} UK",
        ),
        ("AU", "Australia|AUS"),
        ("ZA", "ZA-01|ZA 01"),
        ("DE", "FRA 02|FRA region memo|FRA oldest region cache entry"),
        ("FR", "Paris CDG|France via JP"),
        ("IN", "India|IN-01|印度 Mumbai|India IN|IN 01"),
        (
            "ID",
            "Indonesia|印度尼西亞|印度尼西亚 01|Indonesia India|ID 42",
        ),
        ("TR", "Türkiye|TÜRKIYE"),
        ("CN", "China via Hong Kong|CN 01|中国节点|中國節點"),
        ("KZ", "🇰🇿 Almaty 01|哈萨克 01|哈薩克-01"),
        ("AT", "🇦🇹|奥地利|Vienna 01|AT 01"),
        ("PK", "🇵🇰-01"),
        ("AQ", "🇦🇶 01|🇦🇶 JP"),
        ("SA", "沙特 02"),
        ("CL", "Santiago"),
        ("NG", "Lagos 01"),
        ("IT", "IT Milan|IT milan"),
        ("AE", "阿聯酋 迪拜"),
        ("NO", "NO-1 Norway"),
        ("MY", "MY kul"),
        ("BR", "Sao Paulo|São Paulo"),
        (
            "",
            "shkhk|my node|it works|no country|node id 42|ChinaTown|ÉJapan|Japané|Japan\u{301}|xUnited States|United Statesman|🇽🇽 01|at the gate|🇺🇭🇰|🇭|AS|as|PM|pm|AM|am|is|be|at|Premium PM 8am to 5pm|Node as backup|Standard AS plan|CNN|BUS|USSR|Kraken|Canadair|begin|origin|plain node|高速节点|unknown|unknown region memo|unknown memoized|unknown resolved memoized|In 01|in 01|no route|türkıye|İndia|J\u{200b}apan|SINsg|| ",
        ),
    ] {
        for name in cases.split('|') {
            assert_eq!(
                locate(name).region.as_deref(),
                (!iso.is_empty()).then_some(iso),
                "{name:?}"
            );
        }
    }
}

#[test]
fn web_inventory_names() {
    let regions = [
        ("香港", "HK"),
        ("台灣", "TW"),
        ("日本", "JP"),
        ("新加坡", "SG"),
        ("美國", "US"),
        ("韓國", "KR"),
        ("英國", "GB"),
        ("德國", "DE"),
        ("澳洲", "AU"),
        ("土耳其", "TR"),
    ];
    let tags = ["IPLC", "BGP", "家寬", "解鎖", "0.5x", "2x", "流媒體", ""];
    let mut seed = 7u64;
    for i in 0..120 {
        let (prefix, iso) = regions[i % 10];
        seed = seed * 48271 % 2147483647;
        let tag = tags[(seed * 8 / 2147483647) as usize];
        let name = format!("{prefix} {:02} {tag}", i / 10 + 1);
        assert_eq!(
            locate(name.trim_end()).region.as_deref(),
            Some(iso),
            "{name}"
        );
        for _ in 0..4 {
            seed = seed * 48271 % 2147483647;
        }
    }
}

const WEB: &str = include_str!("../../tests/fixtures/web-region-aliases.txt");

#[test]
fn web_flags_and_alias_alphabets() {
    const FLAGS: &str = "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW";
    for code in FLAGS.split_ascii_whitespace() {
        let flag: String = code
            .bytes()
            .map(|b| char::from_u32(0x1f1e6 + u32::from(b - b'A')).unwrap())
            .collect();
        let found = locate(&flag);
        assert_eq!(found.region.as_deref(), Some(code), "{flag}");
        let p = found.region_centroid.unwrap();
        assert!((-90.0..=90.0).contains(&p.lat) && (-180.0..=180.0).contains(&p.lon));
        assert!(found.city.is_none());
    }
    for a in MATCHER.aliases.iter().filter(|a| a.city.is_none()) {
        let han = |c: char| ('\u{3400}'..='\u{9fff}').contains(&c);
        assert!(a.key.chars().all(|c| c == ' ' || c.is_alphabetic()));
        assert_eq!(a.key.chars().any(han), a.key.chars().all(han));
        assert_eq!(a.bounded, !a.key.chars().any(han));
    }
}

// Independent per-alias oracle for the web's case, boundary and pairwise corpus. Bucket by
// initial to keep the debug suite cheap. All case forms in this corpus preserve byte offsets.
struct RefKey {
    text: String,
    iso: &'static str,
    exact: bool,
    bounded: bool,
    risky: bool,
}

impl RefKey {
    fn accepts(&self, original: &str, lower: &str, offset: usize) -> bool {
        let Some(tail) = lower[offset..].strip_prefix(&self.text) else {
            return false;
        };
        let text = &original[offset..offset + self.text.len()];
        if self.exact && text != self.iso {
            return false;
        }
        let word = |c: char| c.is_alphabetic() || c == '\u{301}';
        if self.text == "港" && tail.chars().next().is_some_and(word) {
            return [
                "iplc", "iepl", "bgp", "cn2", "gia", "专线", "專線", "节点", "節點", "中转", "中轉",
            ]
            .iter()
            .any(|tag| {
                tail.strip_prefix(tag).is_some_and(|rest| {
                    !tag.is_ascii() || !rest.chars().next().is_some_and(|c| word(c) && !han(c))
                })
            });
        }
        if self.bounded
            && (lower[..offset].chars().next_back().is_some_and(word)
                || tail.chars().next().is_some_and(word))
        {
            return false;
        }
        if !self.risky {
            return true;
        }
        (original.trim() == text && text.bytes().all(|c| c.is_ascii_uppercase()))
            || tail
                .trim_start_matches(['-', '_', ' '])
                .starts_with(|c: char| c.is_ascii_digit())
    }
}

struct Oracle(HashMap<char, Vec<RefKey>>);
impl Oracle {
    fn new() -> Self {
        let mut keys = HashMap::<_, Vec<_>>::new();
        for line in WEB.lines() {
            let (iso, aliases) = line.split_once(':').unwrap();
            for key in std::iter::once(iso).chain(aliases.split('|')) {
                let exact = key.eq_ignore_ascii_case(iso)
                    && " IN IT MY NO ID KZ AT BE LU CZ RO IS CL CO SA PK NG EG KH "
                        .contains(&format!(" {iso} "));
                let key = key.to_lowercase();
                let initial = key.chars().next().unwrap();
                let risky = matches!(key.as_str(), "sin" | "lax" | "sea");
                keys.entry(initial).or_default().push(RefKey {
                    text: key,
                    iso,
                    exact,
                    bounded: initial.is_ascii(),
                    risky,
                });
            }
        }
        Self(keys)
    }

    fn expected(&self, name: &str) -> Option<&'static str> {
        let lower = name.to_lowercase();
        let mut expected = None;
        for (i, c) in lower.char_indices() {
            if lower[i..].starts_with("🇯🇵") {
                expected = Some("JP");
                break;
            }
            let Some(keys) = self.0.get(&c) else { continue };
            let best = keys
                .iter()
                .filter(|k| k.accepts(name, &lower, i))
                .min_by_key(|k| Reverse(k.text.len()));
            if let Some(k) = best {
                expected = Some(k.iso);
                break;
            }
        }
        // City-only airport codes that occur in the generated web boundary corpus.
        match lower.as_str() {
            "phx" => Some("US"),
            "pkx" => Some("CN"),
            "lux" => Some("LU"),
            _ if name.starts_with("SAO ") => Some("BR"),
            _ => expected,
        }
    }

    fn check(&self, name: &str) {
        let expected = match name.split_once(' ') {
            Some(("上海" | "北京" | "深圳", exit)) => self.expected(exit).or(Some("CN")),
            _ => self.expected(name),
        };
        assert_eq!(locate(name).region.as_deref(), expected, "{name}");
    }
}

#[test]
fn web_case_forms_surroundings_and_ordered_pairs() {
    let oracle = Oracle::new();
    let mut keys = Vec::new();
    let mut firsts = Vec::new();
    for line in WEB.lines() {
        let (iso, aliases) = line.split_once(':').unwrap();
        keys.push(iso);
        keys.extend(aliases.split('|'));
        firsts.push(aliases.split('|').next().unwrap());
    }
    for tag in [
        "IEPL", "BGP", "CN2", "GIA", "专线", "專線", "中转", "中轉", "節點",
    ] {
        oracle.check(&format!("港{tag}01"));
    }
    for &key in &keys {
        let title: String = key
            .split(' ')
            .map(|word| {
                let mut chars = word.chars();
                chars.next().unwrap().to_uppercase().collect::<String>() + chars.as_str()
            })
            .collect::<Vec<_>>()
            .join(" ");
        let alternating: String = key
            .chars()
            .enumerate()
            .map(|(i, c)| {
                if i % 2 == 1 {
                    c.to_uppercase().to_string()
                } else {
                    c.to_lowercase().to_string()
                }
            })
            .collect();
        for form in [
            key.to_owned(),
            key.to_lowercase(),
            key.to_uppercase(),
            title,
            alternating,
        ] {
            for (prefix, suffix) in [
                ("", ""),
                ("", "-01"),
                ("01 ", " IPLC"),
                ("x", ""),
                ("", "x"),
                ("", "2"),
                ("é", ""),
                ("", "\u{301}"),
                ("🇯🇵 ", ""),
                ("", "节点"),
                ("[", "]"),
            ] {
                oracle.check(&format!("{prefix}{form}{suffix}"));
            }
            oracle.check(&format!("{form}|{form}"));
        }
        for first in &firsts {
            oracle.check(&format!("{key} {first}"));
            oracle.check(&format!("{first} {key}"));
        }
        for &other in &keys {
            if key != other && (key.contains(other) || other.contains(key)) {
                for sep in ["", "-", " "] {
                    oracle.check(&format!("{key}{sep}{other}"));
                }
            }
        }
    }
}

#[test]
fn city_is_specific_to_the_first_region() {
    for (name, iso, city) in [
        ("New York US", "US", Some("new-york")),
        ("us Chicago", "US", Some("chicago")),
        ("US 🇩🇪 Dallas", "US", Some("dallas")),
        ("Miami", "US", Some("miami")),
        ("🇨🇦 YVR", "CA", Some("vancouver")),
        ("Toronto", "CA", Some("toronto")),
        ("🇯🇵 osaka tokyo", "JP", Some("osaka")),
        ("Japan NRT", "JP", Some("tokyo")),
        ("SÃO PAULO", "BR", Some("sao-paulo")),
        ("🇦🇺 MEL", "AU", Some("melbourne")),
        ("CN via Tokyo", "CN", None),
        ("🇦🇶 LAX", "AQ", None),
    ] {
        let found = locate(name);
        assert_eq!(
            (
                found.region.as_deref(),
                found.city.as_ref().map(|s| s.id.as_ref())
            ),
            (Some(iso), city),
            "{name}"
        );
    }
    assert_eq!(locate("anonymous"), Location::default());
}
