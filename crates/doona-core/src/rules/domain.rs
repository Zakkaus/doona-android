//! The values of `DOMAIN`, `DOMAIN-SUFFIX` and `DOMAIN-KEYWORD` rows, read as a source is checked.

use crate::dto::ErrorCode;

/// A `DOMAIN` or `DOMAIN-SUFFIX` value in the form DNS asks for: lower case, without a final dot,
/// and a name outside ASCII in its IDNA form (`中国.cn` is `xn--fiqs8s.cn`). Only an empty value
/// is refused; one that is no name is kept.
pub(super) fn rule_name(value: &str) -> Result<String, ErrorCode> {
    let value = value.strip_suffix('.').unwrap_or(value);
    if value.is_empty() {
        return Err(ErrorCode::ParseFailed);
    }
    if value.is_ascii() {
        return Ok(value.to_ascii_lowercase());
    }
    Ok(match url::Host::parse(value) {
        Ok(url::Host::Domain(name)) => name,
        _ => value.to_lowercase(),
    })
}

/// A `DOMAIN-KEYWORD` value: any text but none.
pub(super) fn keyword(value: &str) -> Result<(), ErrorCode> {
    if value.is_empty() {
        Err(ErrorCode::ParseFailed)
    } else {
        Ok(())
    }
}
