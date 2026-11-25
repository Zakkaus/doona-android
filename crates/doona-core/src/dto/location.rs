use serde::{Deserialize, Serialize};
use std::borrow::Cow;

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct Coordinates {
    pub lat: f64,
    pub lon: f64,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct City {
    pub id: Cow<'static, str>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub names: Option<CityNames>,
    #[serde(flatten)]
    pub coordinates: Coordinates,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct CityNames {
    pub en: Cow<'static, str>,
    #[serde(rename = "zh-TW")]
    pub zh_tw: Cow<'static, str>,
    #[serde(rename = "zh-CN")]
    pub zh_cn: Cow<'static, str>,
}

/// Display hints from the leaf name, not measurements of the server's location.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Location {
    pub region: Option<Cow<'static, str>>,
    pub region_centroid: Option<Coordinates>,
    pub city: Option<City>,
}
