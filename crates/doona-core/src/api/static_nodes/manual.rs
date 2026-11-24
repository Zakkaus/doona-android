use super::{StaticResult, invalid, unsupported};
use crate::dto::ManualNode;
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use percent_encoding::{NON_ALPHANUMERIC, utf8_percent_encode};
use url::{Host, form_urlencoded};

pub(super) fn link(draft: ManualNode) -> Result<(Option<String>, String), StaticResult> {
    let ManualNode {
        name,
        protocol,
        server,
        port,
        username,
        password,
        uuid,
        cipher,
        options,
    } = draft;
    if port == 0 {
        return Err(invalid("port"));
    }
    let server = match server.parse::<std::net::Ipv6Addr>() {
        Ok(ip) => Host::Ipv6(ip),
        Err(_) => Host::parse(&server).map_err(|_| invalid("server"))?,
    };
    let endpoint = format!("{server}:{port}");
    let auth = Credentials {
        username: username.as_deref(),
        password: password.as_deref(),
        uuid: uuid.as_deref(),
        cipher: cipher.as_deref(),
    };
    let mut link = auth.link(&protocol, &endpoint)?;
    if !options.is_empty() {
        link.push('?');
        form_urlencoded::Serializer::new(&mut link)
            .extend_pairs(&options)
            .finish();
    }
    Ok((name, link))
}

struct Credentials<'a> {
    username: Option<&'a str>,
    password: Option<&'a str>,
    uuid: Option<&'a str>,
    cipher: Option<&'a str>,
}

impl Credentials<'_> {
    fn link(&self, scheme: &str, endpoint: &str) -> Result<String, StaticResult> {
        if scheme == "vmess" {
            self.only(&["uuid"])?;
            return Ok(format!(
                "vmess://{}",
                URL_SAFE_NO_PAD.encode(format!("auto:{}@{endpoint}", self.uuid()?))
            ));
        }
        let (username, password) = self.fields(scheme)?;
        Ok(authority(scheme, endpoint, username, password))
    }

    fn fields(&self, scheme: &str) -> Result<(Option<&str>, Option<&str>), StaticResult> {
        let (username, password) = match scheme {
            "vless" => {
                self.only(&["uuid"])?;
                let uuid = self.uuid()?;
                (Some(uuid), None)
            }
            "tuic" | "juicity" => {
                self.only(&["uuid", "password"])?;
                (Some(self.uuid()?), Some(self.password()?))
            }
            "trojan" | "anytls" | "hysteria2" => {
                self.only(&["password"])?;
                (Some(self.password()?), None)
            }
            "ss" => self.shadowsocks()?,
            "socks5" => {
                self.only(&["username", "password"])?;
                (self.username, self.password)
            }
            _ => return Err(unsupported("protocol")),
        };
        Ok((username, password))
    }

    fn shadowsocks(&self) -> Result<(Option<&str>, Option<&str>), StaticResult> {
        self.only(&["cipher", "password"])?;
        let cipher = self
            .cipher
            .filter(|s| !s.is_empty())
            .ok_or_else(|| invalid("cipher"))?;
        Ok((Some(cipher), Some(self.password()?)))
    }

    fn uuid(&self) -> Result<&str, StaticResult> {
        self.uuid
            .filter(|s| uuid::Uuid::parse_str(s).is_ok())
            .ok_or_else(|| invalid("uuid"))
    }
    fn password(&self) -> Result<&str, StaticResult> {
        self.password
            .filter(|s| !s.is_empty())
            .ok_or_else(|| invalid("password"))
    }
    fn only(&self, allowed: &[&str]) -> Result<(), StaticResult> {
        for (key, value) in [
            ("username", self.username),
            ("password", self.password),
            ("uuid", self.uuid),
            ("cipher", self.cipher),
        ] {
            if value.is_some() && !allowed.contains(&key) {
                return Err(unsupported(key));
            }
        }
        Ok(())
    }
}

fn authority(
    scheme: &str,
    endpoint: &str,
    username: Option<&str>,
    password: Option<&str>,
) -> String {
    let enc = |s| utf8_percent_encode(s, NON_ALPHANUMERIC);
    let mut link = format!("{scheme}://");
    if let Some(u) = username {
        link.extend(enc(u));
    }
    if let Some(p) = password {
        link.push(':');
        link.extend(enc(p));
    }
    if username.is_some() || password.is_some() {
        link.push('@');
    }
    link.push_str(endpoint);
    link
}
