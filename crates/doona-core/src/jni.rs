//! The JNI surface of `dev.doona.android.vpn.NativeCore` (`plans/v1/core-api.md`): five instance
//! methods of a Kotlin `object`, so the second argument is that object, which is ignored. Each
//! export only converts arguments and delegates to the process's one [`Core`].
//!
//! `native_method!` wraps every body in `catch_unwind`; [`ThrowInternal`] turns an escaped panic
//! or JNI failure into `IllegalStateException("internal")`, a fixed message that never carries
//! the payload. `start` throws its own error code the same way. The macro's ABI check in
//! jni-macros 0.22.4 expands to `AtomicBool::fetch_update`, deprecated since Rust 1.99, hence
//! `allow(deprecated)` on each declaration (<https://github.com/jni-rs/jni-rs/issues/846>).

use crate::api::{self, Core};
use crate::diag::{Event, crash};
use crate::dto::ErrorCode;
use crate::sys;
use jni::errors::{ErrorPolicy, Result};
use jni::objects::{JObject, JString};
use jni::strings::JNIString;
use jni::sys::{jint, jlong};
use jni::{Env, NativeMethod, jni_str, native_method};
use std::sync::{Mutex, Once, OnceLock};

static CORE: OnceLock<Core> = OnceLock::new();
/// Serializes the first `init`, which builds the runtime and reads the state.
static INIT: Mutex<()> = Mutex::new(());

fn core() -> Option<&'static Core> {
    CORE.get()
}

fn throw_code(env: &mut Env<'_>, code: ErrorCode) {
    if env.exception_check() {
        return;
    }
    // Err(JavaException) is the thrown exception itself; nothing else may follow it.
    let _ = env.throw_new(
        jni_str!("java/lang/IllegalStateException"),
        JNIString::new(code.as_str()),
    );
}

/// Drops a panic payload; its destructor may panic too, and that second payload is leaked.
fn discard_panic_payload(payload: Box<dyn std::any::Any + Send>) {
    let second = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| drop(payload)));
    if let Err(payload) = second {
        let _leaked = std::mem::ManuallyDrop::new(payload);
    }
}

/// Throws `IllegalStateException("internal")` unless an exception is already pending.
pub struct ThrowInternal;

impl<T: Default, E: std::error::Error> ErrorPolicy<T, E> for ThrowInternal {
    type Captures<'unowned_env_local: 'native_method, 'native_method> = ();

    fn on_error<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut Self::Captures<'unowned_env_local, 'native_method>,
        _err: E,
    ) -> Result<T> {
        match core() {
            Some(c) => c
                .diag()
                .record(Event::error("jni").code(ErrorCode::Internal)),
            None => log::error!("jni call failed"),
        }
        throw_code(env, ErrorCode::Internal);
        Ok(T::default())
    }

    fn on_panic<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut Self::Captures<'unowned_env_local, 'native_method>,
        payload: Box<dyn std::any::Any + Send + 'static>,
    ) -> Result<T> {
        discard_panic_payload(payload);
        throw_code(env, ErrorCode::Internal);
        Ok(T::default())
    }
}

/// Replaces the default hook, which prints the payload, with one that does nothing: the payload
/// may quote an address or a URL, and a hook that logged would wait forever on a logger that
/// panicked while it held its lock. Once: the core installs its own hook over this one
/// (`panics.rs`), which a second call would drop, and which writes the crash file and records the
/// panic in the ring before it runs this one.
fn install_panic_hook() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| std::panic::set_hook(Box::new(|_| {})));
}

/// Only the core's own records reach logcat. honk's `tracing` events arrive as `log` records
/// too (no subscriber is installed) and may name nodes, servers or URLs.
fn init_logging() {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("doona-core")
            .with_max_level(log::LevelFilter::Info)
            .with_filter(
                android_logger::FilterBuilder::new()
                    .filter_module("doonacore", log::LevelFilter::Info)
                    .build(),
            ),
    );
}

#[allow(deprecated)]
const _: NativeMethod = native_method! {
    java_type = "dev.doona.android.vpn.NativeCore",
    fn init(data_dir: JString) -> JString,
    export = "Java_dev_doona_android_vpn_NativeCore_init",
    error_policy = ThrowInternal,
};

fn init<'local>(
    env: &mut Env<'local>,
    _this: JObject<'local>,
    data_dir: JString<'local>,
) -> Result<JString<'local>> {
    if core().is_none() {
        let dir = data_dir.try_to_string(env)?;
        let _first = INIT.lock().unwrap_or_else(|e| e.into_inner());
        if core().is_none() {
            init_logging();
            // Before the runtime's threads exist, so it sees every panic.
            install_panic_hook();
            match Core::new(&dir) {
                Ok(c) => {
                    // The panics of threads no runtime of the core runs: JNI callers, carriers.
                    crash::report_otherwise(&std::sync::Arc::downgrade(c.diag()));
                    let _ = CORE.set(c);
                }
                Err(code) => {
                    throw_code(env, code);
                    return Ok(JString::null());
                }
            }
        }
    }
    env.new_string(Core::schema())
}

#[allow(deprecated)]
const _: NativeMethod = native_method! {
    java_type = "dev.doona.android.vpn.NativeCore",
    fn start(tun_fd: jint, config_json: JString) -> void,
    export = "Java_dev_doona_android_vpn_NativeCore_start",
    error_policy = ThrowInternal,
};

fn start<'local>(
    env: &mut Env<'local>,
    _this: JObject<'local>,
    tun_fd: jint,
    config_json: JString<'local>,
) -> Result<()> {
    // First, so a failure anywhere below closes only the core's own copy.
    let tun = match sys::dup_cloexec(tun_fd) {
        Ok(fd) => fd,
        Err(_) => {
            throw_code(env, ErrorCode::Internal);
            return Ok(());
        }
    };
    let config = config_json.try_to_string(env)?;
    let result = match core() {
        Some(c) => c.start(tun, &config),
        None => Err(ErrorCode::Internal),
    };
    if let Err(code) = result {
        throw_code(env, code);
    }
    Ok(())
}

#[allow(deprecated)]
const _: NativeMethod = native_method! {
    java_type = "dev.doona.android.vpn.NativeCore",
    fn stop() -> void,
    export = "Java_dev_doona_android_vpn_NativeCore_stop",
    error_policy = ThrowInternal,
};

fn stop<'local>(_env: &mut Env<'local>, _this: JObject<'local>) -> Result<()> {
    if let Some(c) = core() {
        c.stop();
    }
    Ok(())
}

#[allow(deprecated)]
const _: NativeMethod = native_method! {
    java_type = "dev.doona.android.vpn.NativeCore",
    fn snapshot(since_revision: jlong) -> JString,
    export = "Java_dev_doona_android_vpn_NativeCore_snapshot",
    error_policy = ThrowInternal,
};

fn snapshot<'local>(
    env: &mut Env<'local>,
    _this: JObject<'local>,
    since_revision: jlong,
) -> Result<JString<'local>> {
    let json = match core() {
        Some(c) => c.snapshot(since_revision),
        None => api::snapshot_before_init(),
    };
    env.new_string(json)
}

#[allow(deprecated)]
const _: NativeMethod = native_method! {
    java_type = "dev.doona.android.vpn.NativeCore",
    fn call(command: JString, args_json: JString) -> JString,
    export = "Java_dev_doona_android_vpn_NativeCore_call",
    error_policy = ThrowInternal,
};

fn call<'local>(
    env: &mut Env<'local>,
    _this: JObject<'local>,
    command: JString<'local>,
    args_json: JString<'local>,
) -> Result<JString<'local>> {
    let command = command.try_to_string(env)?;
    let args = args_json.try_to_string(env)?;
    let json = match core() {
        Some(c) => c.call(&command, &args),
        None => api::error_reply(ErrorCode::Internal),
    };
    env.new_string(json)
}
