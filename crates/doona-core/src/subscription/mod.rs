//! Subscriptions: the download ([`fetch`]) and the body parser ([`parse`]), which reads
//! share-link lists itself and structured documents through [`structured`]. The commands that
//! add, update and remove them live in `api/subscriptions.rs`.

pub(crate) mod fetch;
pub(crate) mod parse;
mod structured;
