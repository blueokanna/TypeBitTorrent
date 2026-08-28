//! Windows system integration for the desktop app (Android ignores this
//! module — the OS firewall is managed by the system permission model).
//!
//! Two capabilities, both best-effort with honest result reporting:
//!
//! 1. **ICF / Windows Defender Firewall** — inbound TCP+UDP allow rules for
//!    the listen port on every profile, via `netsh advfirewall` (the same
//!    mechanism every desktop BitTorrent client uses on first run). Adding
//!    a rule needs elevation; when the direct attempt is refused we offer
//!    an elevated retry that triggers one UAC prompt (`Start-Process
//!    -Verb RunAs`), so "自动配置" is fully automatic after the single
//!    consent.
//! 2. **ICS (Internet Connection Sharing)** — optional, admin-gated and
//!    never automatic: configures Windows to share the active internet
//!    connection with a LAN adapter via the `HNetCfg` COM service
//!    (`INetSharingManager`, probed through both historical ProgIDs). This
//!    changes system networking, so it is exposed as an explicit user
//!    action with a warning, and every failure is surfaced verbatim.
//!
//! Everything here runs off the engine thread (the JNI caller is a
//! `Dispatchers.IO` coroutine), so slow system calls never block the
//! engine loop.

/// Base name shared by the TCP and UDP firewall rules.
pub const RULE_BASE: &str = "TypeBitTorrent";

/// A system-configuration result surfaced to the UI.
#[derive(Debug, Clone)]
pub struct SysResult {
    pub ok: bool,
    pub message: String,
}

impl SysResult {
    fn ok(msg: impl Into<String>) -> Self {
        SysResult {
            ok: true,
            message: msg.into(),
        }
    }
    fn fail(msg: impl Into<String>) -> Self {
        SysResult {
            ok: false,
            message: msg.into(),
        }
    }
}

/// Run an external program and capture stdout+stderr. Non-Windows targets
/// report "Only Support Windows" so the shared JNI surface degrades gracefully.
fn run(program: &str, args: &[&str]) -> SysResult {
    #[cfg(target_os = "windows")]
    {
        let output = std::process::Command::new(program).args(args).output();
        match output {
            Ok(o) => {
                let mut msg = String::from_utf8_lossy(&o.stdout).trim().to_string();
                let err = String::from_utf8_lossy(&o.stderr).trim().to_string();
                if !msg.is_empty() && !err.is_empty() {
                    msg.push_str(" / ");
                }
                if !err.is_empty() {
                    msg.push_str(&err);
                }
                let ok = o.status.success();
                if !ok && msg.is_empty() {
                    msg = format!("退出码 {}", o.status.code().unwrap_or(-1));
                }
                SysResult { ok, message: msg }
            }
            Err(e) => SysResult::fail(format!("无法启动 {program}: {e}")),
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = (program, args);
        SysResult::fail("仅 Windows 支持")
    }
}

/// Detect an elevation-required failure and append a plain-language hint.
fn with_elevation_hint(r: SysResult) -> SysResult {
    let lower = r.message.to_lowercase();
    let needs_elevation =
        lower.contains("elevat") || lower.contains("requires elevation") || lower.contains("提升");
    if !r.ok && needs_elevation {
        SysResult::fail(format!("{}（需要以管理员身份运行）", r.message))
    } else {
        r
    }
}

fn tcp_rule_name(port: u16) -> String {
    format!("{RULE_BASE} {port} TCP")
}
fn udp_rule_name(port: u16) -> String {
    format!("{RULE_BASE} {port} UDP")
}

/// The fixed LSD (BEP-14) UDP receive port. Inbound LAN multicast announces
/// arrive on `239.192.152.143:6771`, so Windows Firewall must allow UDP 6771
/// in addition to the BT listen port — otherwise LSD receive is silently
/// blocked and LAN peers can never find each other on the same router.
pub const LSD_UDP_PORT: u16 = 6771;

/// Add inbound TCP+UDP allow rules for `port` on all profiles (direct, no
/// elevation). Also allows UDP 6771 so LSD (BEP-14) multicast announces
/// from LAN neighbours are not dropped by the firewall. The UI calls
/// [`firewall_add_elevated`] when this reports an elevation failure.
pub fn firewall_add(port: u16) -> SysResult {
    let tcp = with_elevation_hint(run(
        "netsh",
        &[
            "advfirewall",
            "firewall",
            "add",
            "rule",
            &format!("name={}", tcp_rule_name(port)),
            "dir=in",
            "action=allow",
            "protocol=TCP",
            &format!("localport={port}"),
            "profile=any",
        ],
    ));
    let udp = with_elevation_hint(run(
        "netsh",
        &[
            "advfirewall",
            "firewall",
            "add",
            "rule",
            &format!("name={}", udp_rule_name(port)),
            "dir=in",
            "action=allow",
            "protocol=UDP",
            &format!("localport={port}"),
            "profile=any",
        ],
    ));
    // LSD (BEP-14): inbound LAN multicast on the fixed 6771 port. Best
    // effort — a bind failure or already-present rule is not fatal.
    let lsd = with_elevation_hint(run(
        "netsh",
        &[
            "advfirewall",
            "firewall",
            "add",
            "rule",
            &format!("name={}", udp_rule_name(LSD_UDP_PORT)),
            "dir=in",
            "action=allow",
            "protocol=UDP",
            &format!("localport={LSD_UDP_PORT}"),
            "profile=any",
        ],
    ));
    if tcp.ok && udp.ok && lsd.ok {
        SysResult::ok(format!(
            "已添加 Windows 防火墙规则（入站 TCP+UDP {port}，LSD UDP {LSD_UDP_PORT}）"
        ))
    } else {
        let mut parts = Vec::new();
        if !tcp.ok {
            parts.push(format!("TCP: {}", tcp.message));
        }
        if !udp.ok {
            parts.push(format!("UDP: {}", udp.message));
        }
        if !lsd.ok {
            parts.push(format!("LSD UDP: {}", lsd.message));
        }
        SysResult::fail(format!("添加防火墙规则失败：{}", parts.join("；")))
    }
}

/// Remove the inbound rules for `port`.
pub fn firewall_remove(port: u16) -> SysResult {
    let tcp = with_elevation_hint(run(
        "netsh",
        &[
            "advfirewall",
            "firewall",
            "delete",
            "rule",
            &format!("name={}", tcp_rule_name(port)),
        ],
    ));
    let udp = with_elevation_hint(run(
        "netsh",
        &[
            "advfirewall",
            "firewall",
            "delete",
            "rule",
            &format!("name={}", udp_rule_name(port)),
        ],
    ));
    let lsd = with_elevation_hint(run(
        "netsh",
        &[
            "advfirewall",
            "firewall",
            "delete",
            "rule",
            &format!("name={}", udp_rule_name(LSD_UDP_PORT)),
        ],
    ));
    if tcp.ok && udp.ok && lsd.ok {
        SysResult::ok(format!("已移除 Windows 防火墙规则（{port}）"))
    } else {
        let mut parts = Vec::new();
        if !tcp.ok {
            parts.push(format!("TCP: {}", tcp.message));
        }
        if !udp.ok {
            parts.push(format!("UDP: {}", udp.message));
        }
        if !lsd.ok {
            parts.push(format!("LSD UDP: {}", lsd.message));
        }
        SysResult::fail(format!("移除防火墙规则失败：{}", parts.join("；")))
    }
}

/// Whether the TCP rule for `port` currently exists (`show` needs no
/// elevation, so this works for every user).
pub fn firewall_status(port: u16) -> SysResult {
    let name = tcp_rule_name(port);
    let r = run(
        "netsh",
        &[
            "advfirewall",
            "firewall",
            "show",
            "rule",
            &format!("name={name}"),
        ],
    );
    let not_found = r.message.contains("No rules match") || r.message.contains("没有规则匹配");
    if r.ok && !not_found {
        SysResult::ok(format!("防火墙规则已存在（{port}，TCP+UDP）"))
    } else {
        SysResult::fail(format!("尚未添加防火墙规则（{port}）"))
    }
}

/// Elevated retry for [`firewall_add`]: writes a tiny PowerShell script,
/// runs it via `Start-Process -Verb RunAs` (one UAC prompt), and waits for
/// completion. The elevated child writes its netsh output to a temp file
/// that we read back so the UI still gets a truthful result.
pub fn firewall_add_elevated(port: u16) -> SysResult {
    #[cfg(target_os = "windows")]
    {
        let dir = std::env::temp_dir();
        let script_path = dir.join(format!("typebit_fw_{port}.ps1"));
        let out_path = dir.join(format!("typebit_fw_{port}.out"));
        let script = format!(
            r#"$ErrorActionPreference = 'Continue'
$out = '{}'
$log = @()
foreach ($rule in @(
  @{{ name = '{}'; proto = 'TCP' }},
  @{{ name = '{}'; proto = 'UDP' }}
)) {{
  $r = & netsh advfirewall firewall add rule name=$($rule.name) dir=in action=allow protocol=$($rule.proto) localport={port} profile=any 2>&1
  $log += "[$($rule.proto)] $($r -join ' ')"
}}
$log | Set-Content -Encoding UTF8 $out
"#,
            out_path.display(),
            tcp_rule_name(port),
            udp_rule_name(port),
        );
        let write_ok = std::fs::write(&script_path, script).is_ok();
        if !write_ok {
            return SysResult::fail("无法写入临时提升脚本");
        }
        let _ = std::fs::remove_file(&out_path);
        let spawn = std::process::Command::new("powershell")
            .args([
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                &format!(
                    "Start-Process powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','{}' -Verb RunAs -Wait",
                    script_path.display()
                ),
            ])
            .output();
        let _ = std::fs::remove_file(&script_path);
        match spawn {
            Ok(o) => {
                let exit = o.status.code().unwrap_or(-1);
                let detail = std::fs::read_to_string(&out_path).unwrap_or_default();
                let _ = std::fs::remove_file(&out_path);
                let detail = detail.trim();
                let added = detail.contains("Ok.") || detail.contains("确定");
                if exit == 0 && added {
                    SysResult::ok(format!("已通过管理员权限添加防火墙规则（TCP+UDP {port}）"))
                } else {
                    SysResult::fail(format!(
                        "管理员提权未完成（退出码 {exit}）：{}",
                        if detail.is_empty() {
                            "用户可能取消了 UAC 提示"
                        } else {
                            detail
                        }
                    ))
                }
            }
            Err(e) => SysResult::fail(format!("无法启动提权流程：{e}")),
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = port;
        SysResult::fail("仅 Windows 支持")
    }
}

/// A PowerShell snippet shared by the ICS helpers: obtains the
/// `NetSharingManager` COM object (probing both historical ProgIDs), then
/// runs `$Action`. Windows-only — non-Windows targets compile it out.
#[cfg(target_os = "windows")]
const ICS_PREFIX: &str = r#"
$ErrorActionPreference = 'Stop'
$mgr = $null
foreach ($progid in @('HNetCfg.NCbs', 'HNetCfg.NetSharingManager')) {
    try { $mgr = New-Object -ComObject $progid; break } catch {}
}
if ($null -eq $mgr) { throw 'NetSharingManager COM (HNetCfg) 不可用' }
"#;

fn run_powershell(body: &str) -> SysResult {
    #[cfg(target_os = "windows")]
    {
        let full = format!("{ICS_PREFIX}\n{body}");
        let dir = std::env::temp_dir();
        let script_path = dir.join("typebit_ics.ps1");
        let out_path = dir.join("typebit_ics.out");
        if std::fs::write(&script_path, full).is_err() {
            return SysResult::fail("无法写入 ICS 临时脚本");
        }
        let _ = std::fs::remove_file(&out_path);
        // Redirect PS stdout+stderr to a file so we capture the raw result.
        let cmdline = format!(
            "-NoProfile -ExecutionPolicy Bypass -File \"{}\" > \"{}\" 2>&1",
            script_path.display(),
            out_path.display()
        );
        let spawn = std::process::Command::new("powershell")
            .args([
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                &cmdline,
            ])
            .output();
        let _ = std::fs::remove_file(&script_path);
        match spawn {
            Ok(o) => {
                let exit = o.status.code().unwrap_or(-1);
                let out = std::fs::read_to_string(&out_path).unwrap_or_default();
                let _ = std::fs::remove_file(&out_path);
                let out = out.trim();
                if exit == 0 {
                    SysResult::ok(if out.is_empty() {
                        "操作成功".to_string()
                    } else {
                        out.to_string()
                    })
                } else {
                    SysResult::fail(if out.is_empty() {
                        format!("操作失败（退出码 {exit}）")
                    } else {
                        out.to_string()
                    })
                }
            }
            Err(e) => SysResult::fail(format!("无法启动 PowerShell：{e}")),
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = body;
        SysResult::fail("仅 Windows 支持")
    }
}

/// Query whether ICS is currently enabled on any connection.
pub fn ics_status() -> SysResult {
    run_powershell(
        r#"
$conns = @($mgr.EnumEveryConnection(1))
$enabled = @()
foreach ($c in $conns) {
    try {
        $cfg = $mgr.INetSharingConfigurationForINetConnection($c)
        if ($cfg.SharingEnabled) {
            $props = $mgr.NetConnectionProps($c)
            $enabled += $props.Name
        }
    } catch {}
}
if ($enabled.Count -gt 0) {
    Write-Output ("ICS 已启用：{0}" -f ($enabled -join ', '))
} else {
    Write-Output 'ICS 未启用'
}
"#,
    )
}

/// Enable ICS: share the first connected (up) adapter as public, use the
/// next LAN adapter as private. Explicit user action — this changes system
/// networking and requires elevation (admin).
pub fn ics_enable() -> SysResult {
    run_powershell(
        r#"
$conns = @($mgr.EnumEveryConnection(1))
$public = $null
$private = $null
foreach ($c in $conns) {
    $props = $mgr.NetConnectionProps($c)
    if ($null -eq $props) { continue }
    # Status 2 = up/connected (NCS_MEDIA_CONNECTED); 7 = disconnected.
    if ($props.Status -eq 2 -and $null -eq $public) { $public = $c }
    elseif ($null -eq $private) { $private = $c }
}
if ($null -eq $public) { throw 'No connected (upstream) network adapter found' }
if ($null -eq $private) { throw 'No LAN adapter available for sharing' }
$pubCfg = $mgr.INetSharingConfigurationForINetConnection($public)
$pubCfg.EnableSharing($true, 0)   # ICSSHARINGTYPE_PUBLIC
$privCfg = $mgr.INetSharingConfigurationForINetConnection($private)
$privCfg.EnableSharing($true, 1)  # ICSSHARINGTYPE_PRIVATE
$pubProps = $mgr.NetConnectionProps($public)
$privProps = $mgr.NetConnectionProps($private)
Write-Output ("ICS is enabled: {0} (upstream) → {1} (LAN)" -f $pubProps.Name, $privProps.Name)
"#,
    )
}

/// Disable ICS on every currently-shared connection.
pub fn ics_disable() -> SysResult {
    run_powershell(
        r#"
$conns = @($mgr.EnumEveryConnection(1))
$disabled = 0
foreach ($c in $conns) {
    try {
        $cfg = $mgr.INetSharingConfigurationForINetConnection($c)
        if ($cfg.SharingEnabled) {
            $cfg.DisableSharing()
            $disabled++
        }
    } catch {}
}
if ($disabled -gt 0) { Write-Output ("{0} shared connections have been disabled." -f $disabled) }
else { Write-Output 'Currently, no shared connections are enabled.' }
"#,
    )
}

/// Serialize a [`SysResult`] as `{"ok":bool,"message":".."}` for the JNI
/// boundary (Kotlin decodes it with kotlinx.serialization).
pub fn sys_result_to_json(r: &SysResult) -> String {
    let mut w = crate::json::JsonWriter::new();
    w.begin_object();
    w.kv_bool("ok", r.ok);
    w.comma();
    w.kv_string("message", &r.message);
    w.end_object();
    w.into_string()
}
