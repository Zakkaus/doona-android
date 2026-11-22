//! Subscriptions: the download ([`fetch`]) and the body parser ([`parse`]). The commands that
//! add, update and remove them live in `api/subscriptions.rs`.

pub(crate) mod fetch;
pub(crate) mod parse;
