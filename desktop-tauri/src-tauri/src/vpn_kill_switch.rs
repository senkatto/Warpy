#![cfg(windows)]

use std::{
    collections::BTreeSet,
    ffi::c_void,
    net::{IpAddr, SocketAddr, ToSocketAddrs},
    os::windows::ffi::OsStrExt,
    path::Path,
    ptr, slice,
};
use windows_sys::{
    core::GUID,
    Win32::{
        Foundation::HANDLE,
        NetworkManagement::{
            IpHelper::{FreeMibTable, GetIfTable2, MIB_IF_ROW2, MIB_IF_TABLE2},
            Ndis::IfOperStatusUp,
            WindowsFilteringPlatform::{
                FwpmEngineClose0, FwpmEngineOpen0, FwpmFilterAdd0, FwpmFreeMemory0,
                FwpmGetAppIdFromFileName0, FwpmSubLayerAdd0, FwpmTransactionAbort0,
                FwpmTransactionBegin0, FwpmTransactionCommit0, FWPM_ACTION0,
                FWPM_CONDITION_ALE_APP_ID, FWPM_CONDITION_FLAGS, FWPM_CONDITION_INTERFACE_INDEX,
                FWPM_CONDITION_IP_LOCAL_INTERFACE, FWPM_CONDITION_IP_REMOTE_ADDRESS,
                FWPM_CONDITION_IP_REMOTE_PORT, FWPM_DISPLAY_DATA0, FWPM_FILTER0,
                FWPM_FILTER_CONDITION0, FWPM_LAYER_ALE_AUTH_CONNECT_V4,
                FWPM_LAYER_ALE_AUTH_CONNECT_V6, FWPM_LAYER_OUTBOUND_IPPACKET_V4,
                FWPM_LAYER_OUTBOUND_IPPACKET_V6, FWPM_LAYER_OUTBOUND_TRANSPORT_V4,
                FWPM_LAYER_OUTBOUND_TRANSPORT_V6, FWPM_SESSION0, FWPM_SESSION_FLAG_DYNAMIC,
                FWPM_SUBLAYER0, FWP_ACTION_BLOCK, FWP_ACTION_PERMIT, FWP_BYTE_ARRAY16,
                FWP_BYTE_ARRAY16_TYPE, FWP_BYTE_BLOB, FWP_BYTE_BLOB_TYPE,
                FWP_CONDITION_FLAG_IS_LOOPBACK, FWP_CONDITION_VALUE0, FWP_CONDITION_VALUE0_0,
                FWP_MATCH_EQUAL, FWP_MATCH_FLAGS_ALL_SET, FWP_UINT16, FWP_UINT32, FWP_UINT64,
                FWP_UINT8, FWP_VALUE0, FWP_VALUE0_0,
            },
        },
        System::Rpc::RPC_C_AUTHN_WINNT,
    },
};

const SUBLAYER_KEY: GUID = GUID::from_u128(0x71a02bd1_d772_4e4c_9982_3ff184eb02f7);
const WARPY_INTERFACE_ALIAS: &str = "warpy-tun";
pub(crate) const COMPETING_VPN_ERROR: &str = "ANOTHER_VPN_ACTIVE";

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum KillSwitchState {
    Off,
    Armed,
    Suppressed(String),
    Error(String),
}

impl KillSwitchState {
    pub(crate) fn label(&self) -> String {
        match self {
            Self::Off => "Off".to_string(),
            Self::Armed => "Armed".to_string(),
            Self::Suppressed(interface) => format!("Suppressed:{interface}"),
            Self::Error(error) => format!("Error:{error}"),
        }
    }
}

pub(crate) struct VpnKillSwitch {
    session: Option<WfpSession>,
    state: KillSwitchState,
}

impl VpnKillSwitch {
    pub(crate) fn new() -> Self {
        Self {
            session: None,
            state: KillSwitchState::Off,
        }
    }

    pub(crate) fn state(&self) -> KillSwitchState {
        self.state.clone()
    }

    pub(crate) fn disarm(&mut self) {
        self.session = None;
        self.state = KillSwitchState::Off;
    }

    pub(crate) fn arm(
        &mut self,
        core_path: &Path,
        config: &str,
    ) -> Result<KillSwitchState, String> {
        self.disarm();
        self.reconcile(core_path, config)
    }

    pub(crate) fn reconcile(
        &mut self,
        core_path: &Path,
        config: &str,
    ) -> Result<KillSwitchState, String> {
        let interfaces = tunnel_interfaces()?;
        if let Some(interface) = interfaces.competing.first() {
            self.session = None;
            self.state = KillSwitchState::Suppressed(interface.clone());
            return Ok(self.state.clone());
        }
        if config_requires_unfiltered_direct_traffic(config)? {
            self.session = None;
            self.state = KillSwitchState::Suppressed("split-tunneling".to_string());
            return Ok(self.state.clone());
        }
        if self.session.is_some() {
            self.state = KillSwitchState::Armed;
            return Ok(self.state.clone());
        }
        let warpy_luid = interfaces
            .warpy_luid
            .ok_or_else(|| "Интерфейс Warpy еще не готов".to_string())?;
        let warpy_index = interfaces
            .warpy_index
            .ok_or_else(|| "Индекс интерфейса Warpy еще не готов".to_string())?;
        let endpoints = allowed_direct_endpoints(config)?;

        match WfpSession::open(core_path, warpy_luid, warpy_index, &endpoints) {
            Ok(session) => {
                self.session = Some(session);
                self.state = KillSwitchState::Armed;
                Ok(self.state.clone())
            }
            Err(error) => {
                self.state = KillSwitchState::Error(error.clone());
                Err(error)
            }
        }
    }
}

struct InterfaceSnapshot {
    warpy_luid: Option<u64>,
    warpy_index: Option<u32>,
    competing: Vec<String>,
}

fn tunnel_interfaces() -> Result<InterfaceSnapshot, String> {
    let mut table: *mut MIB_IF_TABLE2 = ptr::null_mut();
    let status = unsafe { GetIfTable2(&mut table) };
    if status != 0 {
        return Err(format!("Не удалось проверить сетевые интерфейсы: {status}"));
    }
    if table.is_null() {
        return Err("Windows вернула пустой список сетевых интерфейсов".to_string());
    }

    let mut snapshot = InterfaceSnapshot {
        warpy_luid: None,
        warpy_index: None,
        competing: Vec::new(),
    };
    unsafe {
        let rows: &[MIB_IF_ROW2] =
            slice::from_raw_parts((*table).Table.as_ptr(), (*table).NumEntries as usize);
        for row in rows {
            let alias = interface_alias(row);
            if alias.eq_ignore_ascii_case(WARPY_INTERFACE_ALIAS) {
                snapshot.warpy_luid = Some(row.InterfaceLuid.Value);
                snapshot.warpy_index = Some(row.InterfaceIndex);
            } else if row.OperStatus == IfOperStatusUp && looks_like_vpn_interface(&alias) {
                snapshot.competing.push(alias);
            }
        }
        FreeMibTable(table.cast::<c_void>());
    }
    snapshot.competing.sort_unstable();
    snapshot.competing.dedup();
    Ok(snapshot)
}

pub(crate) fn competing_vpn_active() -> Result<bool, String> {
    Ok(!tunnel_interfaces()?.competing.is_empty())
}

fn interface_alias(row: &MIB_IF_ROW2) -> String {
    let length = row
        .Alias
        .iter()
        .position(|character| *character == 0)
        .unwrap_or(row.Alias.len());
    String::from_utf16_lossy(&row.Alias[..length])
}

fn looks_like_vpn_interface(alias: &str) -> bool {
    let alias = alias.to_ascii_lowercase();
    ["tun", "tap", "vpn", "wireguard", "openvpn"]
        .iter()
        .any(|marker| alias.contains(marker))
}

struct WfpSession(isize);

unsafe impl Send for WfpSession {}

impl WfpSession {
    fn open(
        core_path: &Path,
        warpy_luid: u64,
        warpy_index: u32,
        endpoints: &[SocketAddr],
    ) -> Result<Self, String> {
        let mut session_name = wide("Warpy Kill Switch");
        let mut session: FWPM_SESSION0 = unsafe { std::mem::zeroed() };
        session.displayData = FWPM_DISPLAY_DATA0 {
            name: session_name.as_mut_ptr(),
            description: ptr::null_mut(),
        };
        session.flags = FWPM_SESSION_FLAG_DYNAMIC;
        let mut handle: HANDLE = ptr::null_mut();
        check_wfp(
            unsafe {
                FwpmEngineOpen0(
                    ptr::null(),
                    RPC_C_AUTHN_WINNT,
                    ptr::null(),
                    &session,
                    &mut handle,
                )
            },
            "Не удалось открыть WFP",
        )?;
        let session = Self(handle as isize);
        session.install_filters(core_path, warpy_luid, warpy_index, endpoints)?;
        Ok(session)
    }

    fn install_filters(
        &self,
        core_path: &Path,
        warpy_luid: u64,
        warpy_index: u32,
        endpoints: &[SocketAddr],
    ) -> Result<(), String> {
        let handle = self.raw();
        check_wfp(
            unsafe { FwpmTransactionBegin0(handle, 0) },
            "Не удалось начать настройку kill switch",
        )?;
        let result = (|| {
            add_sublayer(handle)?;
            let core_app_id = AppId::from_path(core_path)?;
            let service_path = std::env::current_exe()
                .map_err(|error| format!("Не удалось определить службу Warpy: {error}"))?;
            let service_app_id = AppId::from_path(&service_path)?;
            for (layer, suffix) in [
                (FWPM_LAYER_ALE_AUTH_CONNECT_V4, "IPv4"),
                (FWPM_LAYER_ALE_AUTH_CONNECT_V6, "IPv6"),
            ] {
                add_app_permit(handle, layer, &format!("core {suffix}"), core_app_id.raw())?;
                add_app_permit(
                    handle,
                    layer,
                    &format!("service {suffix}"),
                    service_app_id.raw(),
                )?;
                add_loopback_permit(handle, layer, suffix)?;
                add_interface_permit(handle, layer, suffix, warpy_luid)?;
                add_block_all(handle, layer, suffix)?;
            }
            for (layer, suffix, ipv6) in [
                (FWPM_LAYER_OUTBOUND_IPPACKET_V4, "packet IPv4", false),
                (FWPM_LAYER_OUTBOUND_IPPACKET_V6, "packet IPv6", true),
            ] {
                for endpoint in endpoints
                    .iter()
                    .filter(|endpoint| endpoint.is_ipv6() == ipv6)
                {
                    add_endpoint_address_permit(handle, layer, suffix, endpoint)?;
                }
                add_loopback_permit(handle, layer, suffix)?;
                add_interface_index_permit(handle, layer, suffix, warpy_index)?;
                add_block_all(handle, layer, suffix)?;
            }
            for (layer, suffix, ipv6) in [
                (FWPM_LAYER_OUTBOUND_TRANSPORT_V4, "transport IPv4", false),
                (FWPM_LAYER_OUTBOUND_TRANSPORT_V6, "transport IPv6", true),
            ] {
                for endpoint in endpoints
                    .iter()
                    .filter(|endpoint| endpoint.is_ipv6() == ipv6)
                {
                    add_endpoint_permit(handle, layer, suffix, endpoint)?;
                }
                add_loopback_permit(handle, layer, suffix)?;
                add_interface_permit(handle, layer, suffix, warpy_luid)?;
                add_block_all(handle, layer, suffix)?;
            }
            check_wfp(
                unsafe { FwpmTransactionCommit0(handle) },
                "Не удалось применить kill switch",
            )
        })();
        if result.is_err() {
            unsafe {
                let _ = FwpmTransactionAbort0(handle);
            }
        }
        result
    }

    fn raw(&self) -> HANDLE {
        self.0 as HANDLE
    }
}

impl Drop for WfpSession {
    fn drop(&mut self) {
        unsafe {
            let _ = FwpmEngineClose0(self.raw());
        }
    }
}

struct AppId(*mut FWP_BYTE_BLOB);

impl AppId {
    fn from_path(path: &Path) -> Result<Self, String> {
        let path = wide_os(path);
        let mut app_id = ptr::null_mut();
        check_wfp(
            unsafe { FwpmGetAppIdFromFileName0(path.as_ptr(), &mut app_id) },
            "Не удалось определить VPN-ядро для kill switch",
        )?;
        if app_id.is_null() {
            return Err("WFP вернула пустой идентификатор VPN-ядра".to_string());
        }
        Ok(Self(app_id))
    }

    fn raw(&self) -> *mut FWP_BYTE_BLOB {
        self.0
    }
}

impl Drop for AppId {
    fn drop(&mut self) {
        unsafe {
            FwpmFreeMemory0((&mut self.0 as *mut *mut FWP_BYTE_BLOB).cast());
        }
    }
}

fn add_sublayer(engine: HANDLE) -> Result<(), String> {
    let mut name = wide("Warpy Kill Switch");
    let mut sublayer: FWPM_SUBLAYER0 = unsafe { std::mem::zeroed() };
    sublayer.subLayerKey = SUBLAYER_KEY;
    sublayer.displayData = FWPM_DISPLAY_DATA0 {
        name: name.as_mut_ptr(),
        description: ptr::null_mut(),
    };
    sublayer.weight = 0x7fff;
    check_wfp(
        unsafe { FwpmSubLayerAdd0(engine, &sublayer, ptr::null_mut()) },
        "Не удалось создать слой kill switch",
    )
}

fn add_app_permit(
    engine: HANDLE,
    layer: GUID,
    suffix: &str,
    app_id: *mut FWP_BYTE_BLOB,
) -> Result<(), String> {
    let mut condition = FWPM_FILTER_CONDITION0 {
        fieldKey: FWPM_CONDITION_ALE_APP_ID,
        matchType: FWP_MATCH_EQUAL,
        conditionValue: FWP_CONDITION_VALUE0 {
            r#type: FWP_BYTE_BLOB_TYPE,
            Anonymous: FWP_CONDITION_VALUE0_0 { byteBlob: app_id },
        },
    };
    add_filter(
        engine,
        layer,
        &format!("Warpy {suffix}"),
        FWP_ACTION_PERMIT,
        15,
        slice::from_mut(&mut condition),
    )
}

fn add_loopback_permit(engine: HANDLE, layer: GUID, suffix: &str) -> Result<(), String> {
    let mut condition = FWPM_FILTER_CONDITION0 {
        fieldKey: FWPM_CONDITION_FLAGS,
        matchType: FWP_MATCH_FLAGS_ALL_SET,
        conditionValue: FWP_CONDITION_VALUE0 {
            r#type: FWP_UINT32,
            Anonymous: FWP_CONDITION_VALUE0_0 {
                uint32: FWP_CONDITION_FLAG_IS_LOOPBACK,
            },
        },
    };
    add_filter(
        engine,
        layer,
        &format!("Warpy loopback {suffix}"),
        FWP_ACTION_PERMIT,
        14,
        slice::from_mut(&mut condition),
    )
}

fn add_interface_permit(
    engine: HANDLE,
    layer: GUID,
    suffix: &str,
    mut interface_luid: u64,
) -> Result<(), String> {
    let mut condition = FWPM_FILTER_CONDITION0 {
        fieldKey: FWPM_CONDITION_IP_LOCAL_INTERFACE,
        matchType: FWP_MATCH_EQUAL,
        conditionValue: FWP_CONDITION_VALUE0 {
            r#type: FWP_UINT64,
            Anonymous: FWP_CONDITION_VALUE0_0 {
                uint64: &mut interface_luid,
            },
        },
    };
    add_filter(
        engine,
        layer,
        &format!("Warpy tunnel {suffix}"),
        FWP_ACTION_PERMIT,
        13,
        slice::from_mut(&mut condition),
    )
}

fn add_endpoint_permit(
    engine: HANDLE,
    layer: GUID,
    suffix: &str,
    endpoint: &SocketAddr,
) -> Result<(), String> {
    let port = endpoint.port();
    let mut ipv6 = FWP_BYTE_ARRAY16 {
        byteArray16: [0; 16],
    };
    let address_value = match endpoint.ip() {
        IpAddr::V4(address) => FWP_CONDITION_VALUE0 {
            r#type: FWP_UINT32,
            Anonymous: FWP_CONDITION_VALUE0_0 {
                uint32: u32::from(address),
            },
        },
        IpAddr::V6(address) => {
            ipv6.byteArray16 = address.octets();
            FWP_CONDITION_VALUE0 {
                r#type: FWP_BYTE_ARRAY16_TYPE,
                Anonymous: FWP_CONDITION_VALUE0_0 {
                    byteArray16: &mut ipv6,
                },
            }
        }
    };
    let mut conditions = [
        FWPM_FILTER_CONDITION0 {
            fieldKey: FWPM_CONDITION_IP_REMOTE_ADDRESS,
            matchType: FWP_MATCH_EQUAL,
            conditionValue: address_value,
        },
        FWPM_FILTER_CONDITION0 {
            fieldKey: FWPM_CONDITION_IP_REMOTE_PORT,
            matchType: FWP_MATCH_EQUAL,
            conditionValue: FWP_CONDITION_VALUE0 {
                r#type: FWP_UINT16,
                Anonymous: FWP_CONDITION_VALUE0_0 { uint16: port },
            },
        },
    ];
    add_filter(
        engine,
        layer,
        &format!("Warpy endpoint {endpoint} {suffix}"),
        FWP_ACTION_PERMIT,
        15,
        &mut conditions,
    )
}

fn add_endpoint_address_permit(
    engine: HANDLE,
    layer: GUID,
    suffix: &str,
    endpoint: &SocketAddr,
) -> Result<(), String> {
    let mut ipv6 = FWP_BYTE_ARRAY16 {
        byteArray16: [0; 16],
    };
    let address_value = match endpoint.ip() {
        IpAddr::V4(address) => FWP_CONDITION_VALUE0 {
            r#type: FWP_UINT32,
            Anonymous: FWP_CONDITION_VALUE0_0 {
                uint32: u32::from(address),
            },
        },
        IpAddr::V6(address) => {
            ipv6.byteArray16 = address.octets();
            FWP_CONDITION_VALUE0 {
                r#type: FWP_BYTE_ARRAY16_TYPE,
                Anonymous: FWP_CONDITION_VALUE0_0 {
                    byteArray16: &mut ipv6,
                },
            }
        }
    };
    let mut condition = FWPM_FILTER_CONDITION0 {
        fieldKey: FWPM_CONDITION_IP_REMOTE_ADDRESS,
        matchType: FWP_MATCH_EQUAL,
        conditionValue: address_value,
    };
    add_filter(
        engine,
        layer,
        &format!("Warpy endpoint {} {suffix}", endpoint.ip()),
        FWP_ACTION_PERMIT,
        15,
        slice::from_mut(&mut condition),
    )
}

fn add_interface_index_permit(
    engine: HANDLE,
    layer: GUID,
    suffix: &str,
    interface_index: u32,
) -> Result<(), String> {
    let mut condition = FWPM_FILTER_CONDITION0 {
        fieldKey: FWPM_CONDITION_INTERFACE_INDEX,
        matchType: FWP_MATCH_EQUAL,
        conditionValue: FWP_CONDITION_VALUE0 {
            r#type: FWP_UINT32,
            Anonymous: FWP_CONDITION_VALUE0_0 {
                uint32: interface_index,
            },
        },
    };
    add_filter(
        engine,
        layer,
        &format!("Warpy tunnel {suffix}"),
        FWP_ACTION_PERMIT,
        13,
        slice::from_mut(&mut condition),
    )
}

fn add_block_all(engine: HANDLE, layer: GUID, suffix: &str) -> Result<(), String> {
    add_filter(
        engine,
        layer,
        &format!("Warpy block leaks {suffix}"),
        FWP_ACTION_BLOCK,
        0,
        &mut [],
    )
}

fn add_filter(
    engine: HANDLE,
    layer: GUID,
    title: &str,
    action: u32,
    weight: u8,
    conditions: &mut [FWPM_FILTER_CONDITION0],
) -> Result<(), String> {
    let mut title = wide(title);
    let mut filter: FWPM_FILTER0 = unsafe { std::mem::zeroed() };
    filter.displayData = FWPM_DISPLAY_DATA0 {
        name: title.as_mut_ptr(),
        description: ptr::null_mut(),
    };
    filter.layerKey = layer;
    filter.subLayerKey = SUBLAYER_KEY;
    filter.weight = FWP_VALUE0 {
        r#type: FWP_UINT8,
        Anonymous: FWP_VALUE0_0 { uint8: weight },
    };
    filter.numFilterConditions = conditions.len() as u32;
    filter.filterCondition = conditions.as_mut_ptr();
    let mut filter_action: FWPM_ACTION0 = unsafe { std::mem::zeroed() };
    filter_action.r#type = action;
    filter.action = filter_action;
    let mut filter_id = 0_u64;
    check_wfp(
        unsafe { FwpmFilterAdd0(engine, &filter, ptr::null_mut(), &mut filter_id) },
        "Не удалось добавить правило kill switch",
    )
}

fn check_wfp(code: u32, context: &str) -> Result<(), String> {
    if code == 0 {
        Ok(())
    } else {
        Err(format!("{context} (WFP 0x{code:08X})"))
    }
}

fn wide(value: &str) -> Vec<u16> {
    std::ffi::OsStr::new(value)
        .encode_wide()
        .chain(Some(0))
        .collect()
}

fn wide_os(path: &Path) -> Vec<u16> {
    path.as_os_str().encode_wide().chain(Some(0)).collect()
}

fn allowed_direct_endpoints(config: &str) -> Result<Vec<SocketAddr>, String> {
    let config: serde_json::Value = serde_json::from_str(config)
        .map_err(|error| format!("Не удалось прочитать адреса kill switch: {error}"))?;
    let mut endpoints = BTreeSet::new();

    let outbounds = config
        .get("outbounds")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "В конфигурации отсутствуют VPN-серверы".to_string())?;
    let proxy = outbounds
        .iter()
        .find(|outbound| outbound.get("tag").and_then(serde_json::Value::as_str) == Some("proxy"))
        .ok_or_else(|| "В конфигурации отсутствует VPN-сервер".to_string())?;

    if proxy.get("type").and_then(serde_json::Value::as_str) == Some("selector") {
        let selected_tags = proxy
            .get("outbounds")
            .and_then(serde_json::Value::as_array)
            .ok_or_else(|| "Selector VPN не содержит серверов".to_string())?;
        for tag in selected_tags.iter().filter_map(serde_json::Value::as_str) {
            let outbound = outbounds
                .iter()
                .find(|outbound| {
                    outbound.get("tag").and_then(serde_json::Value::as_str) == Some(tag)
                })
                .ok_or_else(|| format!("Selector VPN ссылается на неизвестный сервер {tag}"))?;
            add_config_endpoint(&mut endpoints, outbound, None)?;
        }
    } else {
        add_config_endpoint(&mut endpoints, proxy, None)?;
    }

    if let Some(servers) = config
        .pointer("/dns/servers")
        .and_then(serde_json::Value::as_array)
    {
        for server in servers {
            if server.get("detour").is_none() {
                add_config_endpoint(&mut endpoints, server, Some(53))?;
            }
        }
    }

    if endpoints.is_empty() {
        return Err("Не удалось определить разрешённые адреса kill switch".to_string());
    }
    Ok(endpoints.into_iter().collect())
}

fn config_requires_unfiltered_direct_traffic(config: &str) -> Result<bool, String> {
    let config: serde_json::Value = serde_json::from_str(config)
        .map_err(|error| format!("Некорректная конфигурация Kill Switch: {error}"))?;
    let Some(route) = config.get("route") else {
        return Ok(false);
    };
    if route.get("final").and_then(serde_json::Value::as_str) == Some("direct") {
        return Ok(true);
    }
    let requires_direct = route
        .get("rules")
        .and_then(serde_json::Value::as_array)
        .is_some_and(|rules| {
            rules.iter().any(|rule| {
                let is_direct = rule.get("action").and_then(serde_json::Value::as_str)
                    == Some("route")
                    && rule.get("outbound").and_then(serde_json::Value::as_str) == Some("direct");
                is_direct
                    && (rule.get("process_name").is_some()
                        || rule.get("domain_suffix").is_some()
                        || rule
                            .get("ip_is_private")
                            .and_then(serde_json::Value::as_bool)
                            == Some(true))
            })
        });
    Ok(requires_direct)
}

fn add_config_endpoint(
    endpoints: &mut BTreeSet<SocketAddr>,
    value: &serde_json::Value,
    default_port: Option<u16>,
) -> Result<(), String> {
    let Some(server) = value.get("server").and_then(serde_json::Value::as_str) else {
        return Ok(());
    };
    let port = value
        .get("server_port")
        .and_then(serde_json::Value::as_u64)
        .and_then(|port| u16::try_from(port).ok())
        .or(default_port)
        .ok_or_else(|| format!("Не указан порт сервера {server}"))?;

    if let Ok(address) = server.parse::<IpAddr>() {
        endpoints.insert(SocketAddr::new(address, port));
        return Ok(());
    }

    let resolved = (server, port)
        .to_socket_addrs()
        .map_err(|error| format!("Не удалось определить IP сервера {server}: {error}"))?;
    endpoints.extend(resolved);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{
        allowed_direct_endpoints, config_requires_unfiltered_direct_traffic,
        looks_like_vpn_interface, tunnel_interfaces, KillSwitchState, VpnKillSwitch,
    };
    use std::{net::SocketAddr, path::Path};

    #[test]
    fn recognizes_common_vpn_interfaces() {
        assert!(looks_like_vpn_interface("throne-tun"));
        assert!(looks_like_vpn_interface("OpenVPN Data Channel Offload"));
        assert!(looks_like_vpn_interface("WireGuard Tunnel"));
        assert!(!looks_like_vpn_interface("Ethernet"));
        assert!(!looks_like_vpn_interface("Wi-Fi"));
    }

    #[test]
    fn extracts_only_required_direct_endpoints() {
        let config = r#"{
          "dns": {"servers": [
            {"tag":"remote","server":"8.8.8.8","server_port":853,"detour":"proxy"},
            {"tag":"local-dns","server":"1.1.1.1","server_port":53}
          ]},
          "outbounds": [
            {"type":"hysteria2","tag":"proxy","server":"203.0.113.40","server_port":2443}
          ]
        }"#;
        let endpoints = allowed_direct_endpoints(config).expect("extract endpoints");

        assert!(endpoints.contains(&"203.0.113.40:2443".parse::<SocketAddr>().unwrap()));
        assert!(endpoints.contains(&"1.1.1.1:53".parse::<SocketAddr>().unwrap()));
        assert!(!endpoints.contains(&"8.8.8.8:853".parse::<SocketAddr>().unwrap()));
    }

    #[test]
    fn extracts_every_selector_server_endpoint() {
        let config = r#"{
          "dns": {"servers": [
            {"tag":"local-dns","server":"1.1.1.1","server_port":53}
          ]},
          "outbounds": [
            {"type":"selector","tag":"proxy","outbounds":["profile-1","profile-2"]},
            {"type":"hysteria2","tag":"profile-1","server":"203.0.113.40","server_port":2443},
            {"type":"trojan","tag":"profile-2","server":"203.0.113.11","server_port":8444}
          ]
        }"#;
        let endpoints = allowed_direct_endpoints(config).expect("extract selector endpoints");

        assert!(endpoints.contains(&"203.0.113.40:2443".parse::<SocketAddr>().unwrap()));
        assert!(endpoints.contains(&"203.0.113.11:8444".parse::<SocketAddr>().unwrap()));
        assert!(endpoints.contains(&"1.1.1.1:53".parse::<SocketAddr>().unwrap()));
    }

    #[test]
    fn detects_configs_that_conflict_with_global_wfp_blocking() {
        let regular = r#"{
          "route": {"final":"proxy","rules":[
            {"domain":["vpn.example.com"],"action":"route","outbound":"direct"},
            {"ip_cidr":["203.0.113.10/32"],"action":"route","outbound":"direct"}
          ]}
        }"#;
        assert!(!config_requires_unfiltered_direct_traffic(regular).unwrap());

        for config in [
            r#"{"route":{"final":"direct","rules":[]}}"#,
            r#"{"route":{"final":"proxy","rules":[{"process_name":["telegram.exe"],"action":"route","outbound":"direct"}]}}"#,
            r#"{"route":{"final":"proxy","rules":[{"domain_suffix":["example.com"],"action":"route","outbound":"direct"}]}}"#,
            r#"{"route":{"final":"proxy","rules":[{"ip_is_private":true,"action":"route","outbound":"direct"}]}}"#,
        ] {
            assert!(config_requires_unfiltered_direct_traffic(config).unwrap());
        }
    }

    #[test]
    fn competing_vpn_is_suppressed_before_wfp_is_opened() {
        let interfaces = tunnel_interfaces().expect("read network interfaces");
        let Some(expected) = interfaces.competing.first() else {
            assert!(
                std::env::var_os("WARPY_EXPECT_COMPETING_VPN").is_none(),
                "expected an active competing VPN interface"
            );
            return;
        };

        let mut kill_switch = VpnKillSwitch::new();
        let state = kill_switch
            .arm(Path::new("missing-core-for-suppression-test.exe"), "{}")
            .expect("suppress kill switch");
        assert_eq!(state, KillSwitchState::Suppressed(expected.clone()));
        assert!(kill_switch.session.is_none());
    }
}
