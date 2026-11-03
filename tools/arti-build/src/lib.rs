use jni::JNIEnv;
use jni::objects::{JClass, JString, JObject, GlobalRef};
use jni::sys::{jint, jstring};
use jni::JavaVM;

use arti_client::TorClient;
use arti_client::config::TorClientConfigBuilder;
use tor_rtcompat::PreferredRuntime;

use std::sync::{Arc, Mutex, Once};
use std::path::PathBuf;
use anyhow::Result;

#[cfg(test)]
mod bridge_config_tests {
    use super::*;
    #[test]
    fn custom_transports_are_included_and_invalid_bridges_are_rejected() {
        let state = PathBuf::from("/tmp/synthetic-arti-state");
        let cache = PathBuf::from("/tmp/synthetic-arti-cache");
        let line = "webtunnel 192.0.2.1:443 0123456789ABCDEF0123456789ABCDEF01234567 url=https://bridge.example.invalid/path ver=0.0.3";
        let mut builder = bridge_builder(state.clone(), cache.clone(), line, "/synthetic/libbitchat_pt.so", "/synthetic/android-trust.pem").unwrap();
        assert_eq!(builder.bridges().bridges().len(), 1);
        assert_eq!(builder.bridges().transports().len(), 1);
        assert!(builder.build().is_ok());
        assert!(bridge_builder(state.clone(), cache.clone(), "invalid", "/synthetic/libbitchat_pt.so", "").is_err());
        assert!(bridge_builder(state, cache, line, "relative-program", "/synthetic/android-trust.pem").is_err());
    }

    #[tokio::test]
    async fn socks_accepts_fragmented_authenticated_request() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let (mut peer, mut server) = tokio::io::duplex(1024);
        let task = tokio::spawn(async move {
            read_socks_request(&mut server, Some(("user", "pass"))).await.unwrap()
        });
        for byte in [5, 1, 2] { peer.write_all(&[byte]).await.unwrap(); }
        let mut response = [0; 2];
        peer.read_exact(&mut response).await.unwrap();
        assert_eq!(response, [5, 2]);
        peer.write_all(b"\x01\x04user\x04pass").await.unwrap();
        peer.read_exact(&mut response).await.unwrap();
        assert_eq!(response, [1, 0]);
        peer.write_all(b"\x05\x01\x00\x03\x15relay.example.invalid\x01\xbb").await.unwrap();
        assert_eq!(task.await.unwrap(), ("relay.example.invalid".into(), 443));
    }
}

fn bridge_builder(state: PathBuf, cache: PathBuf, lines: &str, program: &str, certificate_bundle: &str) -> Result<TorClientConfigBuilder> {
    use arti_client::config::{BridgeConfigBuilder, BoolOrAuto, CfgPath, pt::TransportConfigBuilder};
    anyhow::ensure!(lines.len() <= 65536, "Bridge configuration too large");
    let mut config = TorClientConfigBuilder::from_directories(state, cache);
    let mut protocols = std::collections::BTreeSet::new();
    let mut count = 0;
    for line in lines.lines().map(str::trim).filter(|l| !l.is_empty()) {
        count += 1;
        anyhow::ensure!(count <= 64, "Too many bridges");
        let bridge: BridgeConfigBuilder = line.parse().map_err(|_| anyhow::anyhow!("Invalid bridge"))?;
        if let Some(name) = bridge.get_transport() {
            if name != "-" && name != "<none>" {
                anyhow::ensure!(name == "obfs4" || name == "webtunnel", "Unsupported transport");
                protocols.insert(name.to_string());
            }
        }
        config.bridges().bridges().push(bridge);
    }
    if count > 0 { config.bridges().enabled(BoolOrAuto::Explicit(true)); }
    if !protocols.is_empty() {
        anyhow::ensure!(!protocols.contains("webtunnel") || !certificate_bundle.is_empty(), "Android trust bundle required");
        anyhow::ensure!(std::path::Path::new(program).is_absolute(), "Invalid transport program");
        let mut transport = TransportConfigBuilder::default();
        transport.protocols(protocols.iter().map(|p| p.parse()).collect::<std::result::Result<Vec<_>, _>>()?)
            .path(CfgPath::new_literal(program)).run_on_startup(true);
        if !certificate_bundle.is_empty() {
            anyhow::ensure!(std::path::Path::new(certificate_bundle).is_absolute(), "Invalid certificate bundle");
            transport.arguments(vec!["-ca-file".into(), certificate_bundle.into()]);
        }
        config.bridges().transports().push(transport);
    }
    Ok(config)
}

#[derive(Clone, Default)]
struct NetworkConfig { bridges: String, program: String, user: String, password: String, certificate_bundle: String }
static NETWORK_CONFIG: Mutex<Option<NetworkConfig>> = Mutex::new(None);
static BOOTSTRAP_HEALTH: Mutex<(u64, bool)> = Mutex::new((0, false));
static BOOTSTRAP_TASK: Mutex<Option<tokio::task::JoinHandle<()>>> = Mutex::new(None);

#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_configureNetwork(
    mut env: JNIEnv, _class: JClass, bridges: JString, program: JString, user: JString, password: JString, certificate_bundle: JString,
) -> jint {
    let bridges: String = match env.get_string(&bridges) { Ok(v) => v.into(), Err(_) => return -1 };
    let program: String = match env.get_string(&program) { Ok(v) => v.into(), Err(_) => return -1 };
    let user: String = match env.get_string(&user) { Ok(v) => v.into(), Err(_) => return -1 };
    let password: String = match env.get_string(&password) { Ok(v) => v.into(), Err(_) => return -1 };
    let certificate_bundle: String = match env.get_string(&certificate_bundle) { Ok(v) => v.into(), Err(_) => return -1 };
    if user.len() > 255 || password.len() > 255 || user.is_empty() || password.is_empty() { return -2; }
    if bridge_builder(PathBuf::from("/synthetic/state"), PathBuf::from("/synthetic/cache"), &bridges, &program, &certificate_bundle)
        .and_then(|b| Ok(b.build()?)).is_err() { return -3; }
    *NETWORK_CONFIG.lock().unwrap() = Some(NetworkConfig { bridges, program, user, password, certificate_bundle });
    0
}

#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_isReady(_env: JNIEnv, _class: JClass) -> jint {
    ARTI_CLIENT.lock().unwrap().as_ref().map(|c| c.bootstrap_status().ready_for_traffic() as jint).unwrap_or(0)
}

/// -1 stopped, -2 bootstrap failed; otherwise genuine Arti bootstrap progress.
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_getBootstrapProgress(_env: JNIEnv, _class: JClass) -> jint {
    if BOOTSTRAP_HEALTH.lock().unwrap().1 { return -2; }
    let guard = ARTI_CLIENT.lock().unwrap();
    match guard.as_ref() {
        None => -1,
        Some(client) => {
            let status = client.bootstrap_status();
            if status.ready_for_traffic() { 100 }
            else { (status.as_frac() * 100.0).round().clamp(0.0, 99.0) as jint }
        }
    }
}

// ============================================================================
// Global State
// ============================================================================

/// Global Arti client instance
static ARTI_CLIENT: Mutex<Option<Arc<TorClient<PreferredRuntime>>>> = Mutex::new(None);

/// Global Tokio runtime (must persist for Arti to work)
static TOKIO_RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);

/// Global JavaVM reference (cached on first JNI call)
static JAVA_VM: Mutex<Option<JavaVM>> = Mutex::new(None);

/// Global log callback reference
static LOG_CALLBACK: Mutex<Option<GlobalRef>> = Mutex::new(None);

/// Handle to SOCKS server task (for graceful shutdown)
static SOCKS_TASK: Mutex<Option<tokio::task::JoinHandle<()>>> = Mutex::new(None);

/// Initialization flag
static INIT_ONCE: Once = Once::new();

// ============================================================================
// Logging Integration
// ============================================================================

/// Send log message to Java callback
fn send_log_to_java(message: String) {
    let vm_opt = JAVA_VM.lock().unwrap();
    let callback_opt = LOG_CALLBACK.lock().unwrap();

    if let (Some(vm), Some(callback)) = (vm_opt.as_ref(), callback_opt.as_ref()) {
        if let Ok(mut env) = vm.attach_current_thread() {
            if let Ok(jmessage) = env.new_string(&message) {
                let _ = env.call_method(
                    callback.as_obj(),
                    "onLogLine",
                    "(Ljava/lang/String;)V",
                    &[(&jmessage).into()]
                );
            }
        }
    }
}

/// Macro for logging to both Android logcat and Java callback
macro_rules! log_info {
    ($($arg:tt)*) => {{
        let msg = format!($($arg)*);
        android_logger::log(&format!("Arti: {}", msg));
        send_log_to_java(msg);
    }};
}

macro_rules! log_error {
    ($($arg:tt)*) => {{
        let msg = format!("ERROR: {}", format!($($arg)*));
        android_logger::log(&format!("Arti: {}", msg));
        send_log_to_java(msg);
    }};
}

// ============================================================================
// JNI Functions
// ============================================================================

/// Get Arti version string
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_getVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    // Cache JavaVM on first call
    if JAVA_VM.lock().unwrap().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            *JAVA_VM.lock().unwrap() = Some(vm);
        }
    }

    let version = format!("Arti {} (custom build with rustls)", env!("CARGO_PKG_VERSION"));
    let output = env.new_string(version).expect("Couldn't create java string!");
    output.into_raw()
}

/// Set log callback for Arti logs
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_setLogCallback(
    env: JNIEnv,
    _class: JClass,
    callback: JObject,
) {
    // Cache JavaVM if not already cached
    if JAVA_VM.lock().unwrap().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            *JAVA_VM.lock().unwrap() = Some(vm);
        }
    }

    // Store global reference to callback
    if let Ok(global_ref) = env.new_global_ref(callback) {
        *LOG_CALLBACK.lock().unwrap() = Some(global_ref);
        log_info!("Log callback registered");
    }
}

/// Initialize Arti runtime
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_initialize(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
) -> jint {
    // Cache JavaVM if not already cached
    if JAVA_VM.lock().unwrap().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            *JAVA_VM.lock().unwrap() = Some(vm);
        }
    }

    let data_dir_str: String = match env.get_string(&data_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            log_error!("Failed to convert data_dir: {:?}", e);
            return -1;
        }
    };

    log_info!("AMEx: state changed to Initialized");


    // Initialize Tokio runtime (once)
    INIT_ONCE.call_once(|| {
        match tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
        {
            Ok(rt) => {
                log_info!("Tokio runtime created successfully");
                *TOKIO_RUNTIME.lock().unwrap() = Some(rt);
            }
            Err(e) => {
                log_error!("Failed to create Tokio runtime: {:?}", e);
            }
        }
    });

    // Check if runtime exists
    let runtime_guard = TOKIO_RUNTIME.lock().unwrap();
    let runtime = match runtime_guard.as_ref() {
        Some(rt) => rt,
        None => {
            log_error!("Tokio runtime not initialized");
            return -2;
        }
    };

    // Create config with explicit Android paths
    let data_path = PathBuf::from(data_dir_str);
    let cache_dir = data_path.join("cache");
    let state_dir = data_path.join("state");

    // Create directories if they don't exist
    std::fs::create_dir_all(&cache_dir).ok();
    std::fs::create_dir_all(&state_dir).ok();

    let result: Result<()> = runtime.block_on(async {
        log_info!("Creating Arti client...");


        // Create config with Android-specific directories
        let network = NETWORK_CONFIG.lock().unwrap().clone().unwrap_or_default();
        let config = bridge_builder(state_dir, cache_dir, &network.bridges, &network.program, &network.certificate_bundle)?.build()?;
        let client = Arc::new(TorClient::builder().config(config).create_unbootstrapped()?);
        *ARTI_CLIENT.lock().unwrap() = Some(Arc::clone(&client));
        let epoch = {
            let mut health = BOOTSTRAP_HEALTH.lock().unwrap();
            health.0 = health.0.wrapping_add(1); health.1 = false; health.0
        };
        *BOOTSTRAP_TASK.lock().unwrap() = Some(tokio::spawn(async move {
            if client.bootstrap().await.is_ok() { log_info!("Tor bootstrap completed"); }
            else {
                let mut health = BOOTSTRAP_HEALTH.lock().unwrap();
                if health.0 == epoch { health.1 = true; }
                log_error!("Tor bootstrap failed");
            }
        }));

        Ok(())
    });

    match result {
        Ok(_) => {
            log_info!("Arti initialized successfully");
            0
        }
        Err(e) => {
            log_error!("Failed to initialize Arti");
            -3
        }
    }
}

/// Start SOCKS proxy on specified port
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_startSocksProxy(
    _env: JNIEnv,
    _class: JClass,
    port: jint,
) -> jint {
    log_info!("AMEx: state changed to Starting");
    log_info!("Starting SOCKS proxy on port {}", port);

    // Stop any existing SOCKS server first
    if let Some(handle) = SOCKS_TASK.lock().unwrap().take() {
        log_info!("Aborting previous SOCKS server task");
        handle.abort();
    }

    let client_guard = ARTI_CLIENT.lock().unwrap();
    let client = match client_guard.as_ref() {
        Some(c) => Arc::clone(c),
        None => {
            log_error!("Arti client not initialized - call initialize() first");
            return -1;
        }
    };
    drop(client_guard);

    let runtime_guard = TOKIO_RUNTIME.lock().unwrap();
    let runtime = match runtime_guard.as_ref() {
        Some(rt) => rt,
        None => {
            log_error!("Tokio runtime not initialized");
            return -2;
        }
    };

    // Try to bind IMMEDIATELY to detect port conflicts before returning
    let addr = format!("127.0.0.1:{}", port);

    // Use block_on to synchronously attempt binding
    let bind_result = runtime.block_on(async {
        tokio::net::TcpListener::bind(&addr).await
    });

    let listener = match bind_result {
        Ok(l) => {
            log_info!("SOCKS proxy bound to {}", addr);
            l
        }
        Err(e) => {
            log_error!("Failed to bind SOCKS proxy to {}: {:?}", addr, e);
            return -3;
        }
    };

    // Now spawn the background task with the already-bound listener
    let handle = runtime.spawn(async move {
        log_info!("SOCKS proxy listening on {}", addr);


        let mut connections = tokio::task::JoinSet::new();

        // Accept connections
        loop {
            match listener.accept().await {
                Ok((stream, peer_addr)) => {

                    let client_clone = Arc::clone(&client);

                    connections.spawn(async move {
                        let _ = handle_socks_connection(stream, client_clone).await;
                    });
                    while connections.try_join_next().is_some() {}
                }
                Err(e) => {
                    log_error!("Failed to accept SOCKS connection: {:?}", e);
                    break; // Exit loop on error
                }
            }
        }

        log_info!("SOCKS proxy task exiting");
    });

    // Store handle for cleanup
    *SOCKS_TASK.lock().unwrap() = Some(handle);

    log_info!("SOCKS proxy started on port {}", port);
    0
}

/// Handle a single SOCKS connection
async fn handle_socks_connection(
    mut stream: tokio::net::TcpStream,
    client: Arc<TorClient<PreferredRuntime>>,
) -> Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let config = NETWORK_CONFIG.lock().unwrap().clone().unwrap_or_default();
    let credentials = if config.user.is_empty() { None } else { Some((config.user.as_str(), config.password.as_str())) };
    let (target_host, target_port) = tokio::time::timeout(
        std::time::Duration::from_secs(15), read_socks_request(&mut stream, credentials)
    ).await??;

    // Establish Tor connection
    let tor_stream = match client.connect((target_host.as_str(), target_port)).await {
        Ok(s) => s,
        Err(e) => {
            log_error!("Tor connection failed");
            // Send SOCKS5 error: general failure
            stream.write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
            return Err(e.into());
        }
    };



    // Send SOCKS5 success response
    stream.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;

    // Bidirectional data forwarding
    let (mut client_read, mut client_write) = stream.split();
    let (mut tor_read, mut tor_write) = tor_stream.split();

    let client_to_tor = async {
        tokio::io::copy(&mut client_read, &mut tor_write).await
    };

    let tor_to_client = async {
        tokio::io::copy(&mut tor_read, &mut client_write).await
    };

    // Run both directions concurrently, exit when either completes
    tokio::select! {
        result = client_to_tor => {
            if let Err(ref e) = result {
                log_error!("Client->Tor copy error: {:?}", e);
            }
        }
        result = tor_to_client => {
            if let Err(ref e) = result {
                log_error!("Tor->Client copy error: {:?}", e);
            }
        }
    };

    log_info!("SOCKS connection closed for {}:{}", target_host, target_port);

    Ok(())
}

/// Stop Arti and cleanup
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_stop(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    log_info!("AMEx: state changed to Stopping");
    log_info!("Stopping Arti...");

    // Abort SOCKS proxy task (releases the port)
    if let Some(handle) = SOCKS_TASK.lock().unwrap().take() {
        log_info!("Aborting SOCKS server task");
        handle.abort();
    }

    // Give the abort a moment to complete and release the port
    if let Some(rt) = TOKIO_RUNTIME.lock().unwrap().as_ref() {
        rt.block_on(async {
            tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
        });
    }

    { let mut health = BOOTSTRAP_HEALTH.lock().unwrap(); health.0 = health.0.wrapping_add(1); health.1 = false; }
    if let Some(task) = BOOTSTRAP_TASK.lock().unwrap().take() { task.abort(); }
    *ARTI_CLIENT.lock().unwrap() = None;
    *NETWORK_CONFIG.lock().unwrap() = None;

    log_info!("AMEx: state changed to Stopped");
    log_info!("Arti stopped successfully");

    0
}

// ============================================================================
// Android Logger (simple implementation)
// ============================================================================

mod android_logger {
    use std::ffi::CString;

    #[allow(non_camel_case_types)]
    type c_int = i32;

    #[allow(non_camel_case_types)]
    type c_char = i8;

    #[cfg(target_os = "android")]
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    const ANDROID_LOG_INFO: c_int = 4;

    pub fn log(message: &str) {
        #[cfg(not(target_os = "android"))] let _ = message;
        #[cfg(target_os = "android")] unsafe {
            let tag = CString::new("ArtiNative").unwrap();
            let text = CString::new(message).unwrap();
            __android_log_write(ANDROID_LOG_INFO, tag.as_ptr() as *const c_char, text.as_ptr() as *const c_char);
        }
    }
}

async fn read_socks_request<S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin>(
    stream: &mut S, credentials: Option<(&str, &str)>,
) -> Result<(String, u16)> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let version = stream.read_u8().await?;
    let count = stream.read_u8().await? as usize;
    anyhow::ensure!(version == 5 && count > 0, "Invalid SOCKS greeting");
    let mut methods = vec![0; count]; stream.read_exact(&mut methods).await?;
    let method = if credentials.is_some() { 2 } else { 0 };
    if !methods.contains(&method) { stream.write_all(&[5, 255]).await?; anyhow::bail!("SOCKS auth required"); }
    stream.write_all(&[5, method]).await?;
    if let Some((user, password)) = credentials {
        anyhow::ensure!(stream.read_u8().await? == 1, "Invalid auth version");
        let mut u = vec![0; stream.read_u8().await? as usize]; stream.read_exact(&mut u).await?;
        let mut p = vec![0; stream.read_u8().await? as usize]; stream.read_exact(&mut p).await?;
        let valid = u == user.as_bytes() && p == password.as_bytes();
        stream.write_all(&[1, if valid {0} else {1}]).await?;
        anyhow::ensure!(valid, "Invalid credentials");
    }
    let mut head = [0;4]; stream.read_exact(&mut head).await?;
    anyhow::ensure!(head[0] == 5 && head[1] == 1 && head[2] == 0, "Unsupported SOCKS request");
    let host = match head[3] {
        1 => { let mut ip=[0;4]; stream.read_exact(&mut ip).await?; std::net::Ipv4Addr::from(ip).to_string() },
        4 => { let mut ip=[0;16]; stream.read_exact(&mut ip).await?; std::net::Ipv6Addr::from(ip).to_string() },
        3 => { let n=stream.read_u8().await? as usize; anyhow::ensure!(n > 0, "Empty host");
            let mut name=vec![0;n]; stream.read_exact(&mut name).await?; String::from_utf8(name)? },
        _ => anyhow::bail!("Unsupported address type"),
    };
    let port = stream.read_u16().await?;
    anyhow::ensure!(port > 0, "Invalid target port");
    Ok((host, port))
}
