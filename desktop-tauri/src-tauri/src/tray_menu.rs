use serde::{Deserialize, Serialize};
use tauri::{
    image::Image,
    menu::{
        CheckMenuItem, IconMenuItem, Menu, MenuItem, MenuItemKind, PredefinedMenuItem, Submenu,
    },
    path::BaseDirectory,
    Manager, Runtime,
};

pub(crate) const TRAY_ID: &str = "warpy-main";
pub(crate) const TRAY_COMMAND_EVENT: &str = "warpy://tray-command";
const MAX_TRAY_PROFILES: usize = 2000;
const GROUP_PAGE_SIZE: usize = 14;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct TrayMenuSnapshot {
    status: String,
    language: String,
    active: usize,
    profiles: Vec<TrayProfileSnapshot>,
}

impl Default for TrayMenuSnapshot {
    fn default() -> Self {
        Self {
            status: "stopped".to_string(),
            language: "ru".to_string(),
            active: 0,
            profiles: Vec::new(),
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TrayProfileSnapshot {
    name: String,
    country_code: Option<String>,
    group: Option<String>,
}

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "camelCase")]
pub(crate) enum TrayCommand {
    Toggle,
    SelectProfile { index: usize },
}

pub(crate) struct RenderedTrayMenu<R: Runtime> {
    pub(crate) menu: Menu<R>,
    pub(crate) tooltip: String,
}

#[derive(Debug, PartialEq, Eq)]
struct PreparedTrayMenu {
    status: String,
    toggle: String,
    toggle_enabled: bool,
    profiles_label: String,
    open: String,
    quit: String,
    tooltip: String,
    groups: Vec<PreparedGroup>,
    ungrouped: Vec<PreparedProfile>,
}

#[derive(Debug, PartialEq, Eq)]
struct PreparedGroup {
    key: String,
    name: String,
    profiles: Vec<PreparedProfile>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct PreparedProfile {
    index: usize,
    name: String,
    country_code: Option<String>,
    active: bool,
}

struct TrayLabels {
    stopped: &'static str,
    connecting: &'static str,
    connected: &'static str,
    error: &'static str,
    connect: &'static str,
    disconnect: &'static str,
    cancel: &'static str,
    reset_error: &'static str,
    profiles: &'static str,
    open: &'static str,
    quit: &'static str,
}

pub(crate) fn build<R: Runtime, M: Manager<R>>(
    manager: &M,
    snapshot: &TrayMenuSnapshot,
) -> Result<RenderedTrayMenu<R>, String> {
    let prepared = prepare(snapshot)?;
    let menu = Menu::new(manager).map_err(|error| error.to_string())?;

    let status = MenuItem::with_id(
        manager,
        "status",
        menu_text(&prepared.status),
        false,
        None::<&str>,
    )
    .map_err(|error| error.to_string())?;
    menu.append(&status).map_err(|error| error.to_string())?;

    let toggle = MenuItem::with_id(
        manager,
        "toggle",
        menu_text(&prepared.toggle),
        prepared.toggle_enabled,
        None::<&str>,
    )
    .map_err(|error| error.to_string())?;
    menu.append(&toggle).map_err(|error| error.to_string())?;
    append_menu_separator(manager, &menu)?;

    if prepared.toggle_enabled {
        let profiles_menu = Submenu::with_id(
            manager,
            "profiles",
            menu_text(&prepared.profiles_label),
            true,
        )
        .map_err(|error| error.to_string())?;
        for group in &prepared.groups {
            let label = format!("{} · {}", group.name, group.profiles.len());
            let submenu = Submenu::with_id(
                manager,
                format!("group:{}", group.profiles[0].index),
                menu_text(&label),
                true,
            )
            .map_err(|error| error.to_string())?;
            append_group_profiles(manager, &submenu, group)?;
            profiles_menu
                .append(&submenu)
                .map_err(|error| error.to_string())?;
        }
        if !prepared.groups.is_empty() && !prepared.ungrouped.is_empty() {
            append_submenu_separator(manager, &profiles_menu)?;
        }
        for profile in &prepared.ungrouped {
            let item = profile_item(manager, profile)?;
            profiles_menu
                .append(&item)
                .map_err(|error| error.to_string())?;
        }
        menu.append(&profiles_menu)
            .map_err(|error| error.to_string())?;
        append_menu_separator(manager, &menu)?;
    }

    let open = MenuItem::with_id(
        manager,
        "show",
        menu_text(&prepared.open),
        true,
        None::<&str>,
    )
    .map_err(|error| error.to_string())?;
    menu.append(&open).map_err(|error| error.to_string())?;
    let quit = MenuItem::with_id(
        manager,
        "quit",
        menu_text(&prepared.quit),
        true,
        None::<&str>,
    )
    .map_err(|error| error.to_string())?;
    menu.append(&quit).map_err(|error| error.to_string())?;

    Ok(RenderedTrayMenu {
        menu,
        tooltip: prepared.tooltip,
    })
}

fn append_group_profiles<R: Runtime, M: Manager<R>>(
    manager: &M,
    submenu: &Submenu<R>,
    group: &PreparedGroup,
) -> Result<(), String> {
    if group.profiles.len() <= GROUP_PAGE_SIZE {
        for profile in &group.profiles {
            let item = profile_item(manager, profile)?;
            submenu.append(&item).map_err(|error| error.to_string())?;
        }
    } else {
        for page in group.profiles.chunks(GROUP_PAGE_SIZE) {
            let page_menu = Submenu::with_id(
                manager,
                format!("group-page:{}", page[0].index),
                menu_text(&profile_page_label(page)),
                true,
            )
            .map_err(|error| error.to_string())?;
            for profile in page {
                let item = profile_item(manager, profile)?;
                page_menu.append(&item).map_err(|error| error.to_string())?;
            }
            submenu
                .append(&page_menu)
                .map_err(|error| error.to_string())?;
        }
    }
    Ok(())
}

pub(crate) fn command_from_menu_id(id: &str) -> Option<TrayCommand> {
    if id == "toggle" {
        return Some(TrayCommand::Toggle);
    }
    let index = id.strip_prefix("profile:")?.parse::<usize>().ok()?;
    (index < MAX_TRAY_PROFILES).then_some(TrayCommand::SelectProfile { index })
}

fn profile_item<R: Runtime, M: Manager<R>>(
    manager: &M,
    profile: &PreparedProfile,
) -> Result<MenuItemKind<R>, String> {
    if let Some(icon) = profile_flag_icon(manager, profile.country_code.as_deref()) {
        let label = if profile.active {
            format!("✓  {}", profile.name)
        } else {
            profile.name.clone()
        };
        return IconMenuItem::with_id(
            manager,
            format!("profile:{}", profile.index),
            menu_text(&label),
            true,
            Some(icon),
            None::<&str>,
        )
        .map(MenuItemKind::Icon)
        .map_err(|error| error.to_string());
    }

    CheckMenuItem::with_id(
        manager,
        format!("profile:{}", profile.index),
        menu_text(&profile.name),
        true,
        profile.active,
        None::<&str>,
    )
    .map(MenuItemKind::Check)
    .map_err(|error| error.to_string())
}

fn profile_flag_icon<R: Runtime, M: Manager<R>>(
    manager: &M,
    country_code: Option<&str>,
) -> Option<Image<'static>> {
    let country_code = country_code?;
    let path = manager
        .path()
        .resolve(format!("flags/{country_code}.png"), BaseDirectory::Resource)
        .ok()?;
    Image::from_path(path).ok()
}

fn append_menu_separator<R: Runtime, M: Manager<R>>(
    manager: &M,
    menu: &Menu<R>,
) -> Result<(), String> {
    let separator = PredefinedMenuItem::separator(manager).map_err(|error| error.to_string())?;
    menu.append(&separator).map_err(|error| error.to_string())
}

fn append_submenu_separator<R: Runtime, M: Manager<R>>(
    manager: &M,
    menu: &Submenu<R>,
) -> Result<(), String> {
    let separator = PredefinedMenuItem::separator(manager).map_err(|error| error.to_string())?;
    menu.append(&separator).map_err(|error| error.to_string())
}

fn prepare(snapshot: &TrayMenuSnapshot) -> Result<PreparedTrayMenu, String> {
    if snapshot.profiles.len() > MAX_TRAY_PROFILES {
        return Err("Слишком много профилей для меню Warpy".to_string());
    }
    if !snapshot.profiles.is_empty() && snapshot.active >= snapshot.profiles.len() {
        return Err("Некорректный активный профиль меню Warpy".to_string());
    }

    let labels = labels(&snapshot.language);
    let (status_base, toggle) = match snapshot.status.as_str() {
        "stopped" => (labels.stopped, labels.connect),
        "connecting" => (labels.connecting, labels.cancel),
        "connected" => (labels.connected, labels.disconnect),
        "error" => (labels.error, labels.reset_error),
        _ => return Err("Некорректный статус меню Warpy".to_string()),
    };

    let mut groups: Vec<PreparedGroup> = Vec::new();
    let mut ungrouped = Vec::new();
    for (index, profile) in snapshot.profiles.iter().enumerate() {
        let prepared = PreparedProfile {
            index,
            name: clean_text(&profile.name, "VPN profile", 80),
            country_code: normalized_country_code(profile.country_code.as_deref()),
            active: index == snapshot.active,
        };
        let group_key = profile
            .group
            .as_deref()
            .map(|value| clean_text(value, "", 256))
            .filter(|value| !value.is_empty());
        if let Some(group_key) = group_key {
            if let Some(existing) = groups.iter_mut().find(|item| item.key == group_key) {
                existing.profiles.push(prepared);
            } else {
                groups.push(PreparedGroup {
                    name: group_key.chars().take(48).collect(),
                    key: group_key,
                    profiles: vec![prepared],
                });
            }
        } else {
            ungrouped.push(prepared);
        }
    }
    for group in &mut groups {
        group.profiles.sort_by(|left, right| {
            left.name
                .to_lowercase()
                .cmp(&right.name.to_lowercase())
                .then(left.index.cmp(&right.index))
        });
    }

    let active_name = snapshot
        .profiles
        .get(snapshot.active)
        .map(|profile| clean_text(&profile.name, "", 80))
        .filter(|name| !name.is_empty());
    let status = active_name
        .as_deref()
        .map(|name| format!("{status_base} · {name}"))
        .unwrap_or_else(|| status_base.to_string());
    let tooltip = clean_text(&format!("Warpy · {status}"), "Warpy", 120);

    Ok(PreparedTrayMenu {
        status,
        toggle: toggle.to_string(),
        toggle_enabled: !snapshot.profiles.is_empty(),
        profiles_label: labels.profiles.to_string(),
        open: labels.open.to_string(),
        quit: labels.quit.to_string(),
        tooltip,
        groups,
        ungrouped,
    })
}

fn profile_page_label(profiles: &[PreparedProfile]) -> String {
    let first = clean_text(&profiles[0].name, "VPN", 22);
    let last = clean_text(&profiles[profiles.len() - 1].name, "VPN", 22);
    if first == last {
        format!("{first} ({})", profiles.len())
    } else {
        format!("{first} — {last} ({})", profiles.len())
    }
}

fn labels(language: &str) -> TrayLabels {
    if language.eq_ignore_ascii_case("ru") {
        TrayLabels {
            stopped: "VPN выключен",
            connecting: "Подключение...",
            connected: "Подключено",
            error: "Ошибка подключения",
            connect: "Подключить",
            disconnect: "Отключить",
            cancel: "Отменить подключение",
            reset_error: "Сбросить ошибку",
            profiles: "Профили",
            open: "Открыть Warpy",
            quit: "Выход",
        }
    } else {
        TrayLabels {
            stopped: "VPN is off",
            connecting: "Connecting...",
            connected: "Connected",
            error: "Connection error",
            connect: "Connect",
            disconnect: "Disconnect",
            cancel: "Cancel connection",
            reset_error: "Clear error",
            profiles: "Profiles",
            open: "Open Warpy",
            quit: "Quit",
        }
    }
}

fn normalized_country_code(value: Option<&str>) -> Option<String> {
    let value = value?.trim();
    (value.len() == 2 && value.bytes().all(|byte| byte.is_ascii_alphabetic()))
        .then(|| value.to_ascii_lowercase())
}

fn clean_text(value: &str, fallback: &str, max_chars: usize) -> String {
    let collapsed = value
        .chars()
        .map(|character| {
            if character.is_control() {
                ' '
            } else {
                character
            }
        })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    let value = if collapsed.is_empty() {
        fallback
    } else {
        &collapsed
    };
    value.chars().take(max_chars).collect()
}

fn menu_text(value: &str) -> String {
    value.replace('&', "&&")
}

#[cfg(test)]
mod tests {
    use super::{
        command_from_menu_id, prepare, profile_page_label, TrayCommand, TrayMenuSnapshot,
        TrayProfileSnapshot, GROUP_PAGE_SIZE,
    };

    #[test]
    fn prepares_grouped_profiles_and_localized_actions() {
        let snapshot = TrayMenuSnapshot {
            status: "connected".to_string(),
            language: "ru".to_string(),
            active: 1,
            profiles: vec![
                TrayProfileSnapshot {
                    name: "Amsterdam".to_string(),
                    country_code: Some("NL".to_string()),
                    group: Some("BLANCVPN".to_string()),
                },
                TrayProfileSnapshot {
                    name: "Fallback".to_string(),
                    country_code: None,
                    group: None,
                },
            ],
        };

        let menu = prepare(&snapshot).expect("prepare tray menu");
        assert_eq!(menu.status, "Подключено · Fallback");
        assert_eq!(menu.toggle, "Отключить");
        assert_eq!(menu.profiles_label, "Профили");
        assert_eq!(menu.groups.len(), 1);
        assert_eq!(menu.groups[0].profiles[0].index, 0);
        assert_eq!(
            menu.groups[0].profiles[0].country_code.as_deref(),
            Some("nl")
        );
        assert_eq!(menu.ungrouped[0].index, 1);
        assert!(menu.ungrouped[0].active);
    }

    #[test]
    fn sanitizes_labels_and_rejects_invalid_snapshots() {
        let snapshot = TrayMenuSnapshot {
            status: "stopped".to_string(),
            language: "en".to_string(),
            active: 0,
            profiles: vec![TrayProfileSnapshot {
                name: "  R&D\nserver  ".to_string(),
                country_code: Some("invalid".to_string()),
                group: Some("  Work\tVPN  ".to_string()),
            }],
        };
        let menu = prepare(&snapshot).expect("prepare tray menu");
        assert_eq!(menu.status, "VPN is off · R&D server");
        assert_eq!(menu.groups[0].name, "Work VPN");
        assert_eq!(menu.groups[0].profiles[0].country_code, None);

        let invalid = TrayMenuSnapshot {
            status: "unknown".to_string(),
            ..TrayMenuSnapshot::default()
        };
        assert!(prepare(&invalid).is_err());
    }

    #[test]
    fn keeps_groups_distinct_when_their_display_labels_are_truncated() {
        let shared_prefix = "A".repeat(48);
        let snapshot = TrayMenuSnapshot {
            status: "stopped".to_string(),
            language: "en".to_string(),
            active: 0,
            profiles: vec![
                TrayProfileSnapshot {
                    name: "First".to_string(),
                    country_code: None,
                    group: Some(format!("{shared_prefix}-one")),
                },
                TrayProfileSnapshot {
                    name: "Second".to_string(),
                    country_code: None,
                    group: Some(format!("{shared_prefix}-two")),
                },
            ],
        };

        let menu = prepare(&snapshot).expect("prepare tray menu");
        assert_eq!(menu.groups.len(), 2);
        assert_eq!(menu.groups[0].name, menu.groups[1].name);
        assert_ne!(menu.groups[0].key, menu.groups[1].key);
    }

    #[test]
    fn sorts_and_pages_large_groups() {
        let snapshot = TrayMenuSnapshot {
            status: "stopped".to_string(),
            language: "en".to_string(),
            active: 0,
            profiles: (0..31)
                .rev()
                .map(|index| TrayProfileSnapshot {
                    name: format!("Server {index:02}"),
                    country_code: None,
                    group: Some("Provider".to_string()),
                })
                .collect(),
        };

        let menu = prepare(&snapshot).expect("prepare tray menu");
        let profiles = &menu.groups[0].profiles;
        assert_eq!(profiles[0].name, "Server 00");
        assert_eq!(profiles[30].name, "Server 30");
        let pages = profiles.chunks(GROUP_PAGE_SIZE).collect::<Vec<_>>();
        assert_eq!(
            pages.iter().map(|page| page.len()).collect::<Vec<_>>(),
            [14, 14, 3]
        );
        assert_eq!(profile_page_label(pages[0]), "Server 00 — Server 13 (14)");
    }

    #[test]
    fn accepts_only_known_tray_command_ids() {
        assert_eq!(command_from_menu_id("toggle"), Some(TrayCommand::Toggle));
        assert_eq!(
            command_from_menu_id("profile:42"),
            Some(TrayCommand::SelectProfile { index: 42 })
        );
        assert_eq!(command_from_menu_id("profile:2000"), None);
        assert_eq!(command_from_menu_id("profile:not-a-number"), None);
        assert_eq!(command_from_menu_id("quit"), None);
    }
}
