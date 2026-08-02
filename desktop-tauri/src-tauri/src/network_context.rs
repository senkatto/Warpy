#![cfg(windows)]

use serde::{Deserialize, Serialize};
use windows::Win32::{
    Networking::NetworkListManager::{
        INetworkListManager, NetworkListManager, NLM_ENUM_NETWORK_CONNECTED, NLM_NETWORK_CATEGORY,
        NLM_NETWORK_CATEGORY_DOMAIN_AUTHENTICATED, NLM_NETWORK_CATEGORY_PRIVATE,
        NLM_NETWORK_CATEGORY_PUBLIC,
    },
    System::Com::{
        CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_ALL, COINIT_MULTITHREADED,
    },
};

#[derive(Clone, Copy, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum NetworkTrust {
    Trusted,
    Untrusted,
    #[default]
    Unknown,
}

#[derive(Clone, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NetworkContextSnapshot {
    pub(crate) trust: NetworkTrust,
    pub(crate) internet: bool,
    pub(crate) generation: u64,
}

pub(crate) struct NetworkContextReader {
    com_initialized: bool,
}

impl NetworkContextReader {
    pub(crate) fn new() -> Result<Self, String> {
        unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }
            .ok()
            .map_err(|error| format!("Network List Manager initialization failed: {error}"))?;
        Ok(Self {
            com_initialized: true,
        })
    }

    pub(crate) fn read(&self, generation: u64) -> Result<NetworkContextSnapshot, String> {
        let manager: INetworkListManager =
            unsafe { CoCreateInstance(&NetworkListManager, None, CLSCTX_ALL) }
                .map_err(|error| format!("Network List Manager is unavailable: {error}"))?;
        let internet = unsafe { manager.IsConnectedToInternet() }
            .map_err(|error| format!("Network connectivity lookup failed: {error}"))?
            .0
            != 0;
        if !internet {
            return Ok(NetworkContextSnapshot {
                generation,
                ..NetworkContextSnapshot::default()
            });
        }

        let networks = unsafe { manager.GetNetworks(NLM_ENUM_NETWORK_CONNECTED) }
            .map_err(|error| format!("Connected network lookup failed: {error}"))?;
        let mut categories = Vec::new();
        loop {
            let mut item = [None];
            let mut fetched = 0_u32;
            unsafe { networks.Next(&mut item, Some(&mut fetched)) }
                .map_err(|error| format!("Connected network enumeration failed: {error}"))?;
            if fetched == 0 {
                break;
            }
            let Some(network) = item[0].take() else {
                continue;
            };
            let network_has_internet = unsafe { network.IsConnectedToInternet() }
                .map(|value| value.0 != 0)
                .unwrap_or(false);
            if !network_has_internet {
                continue;
            }
            categories.push(unsafe { network.GetCategory() }.ok());
        }

        Ok(NetworkContextSnapshot {
            trust: classify_categories(&categories),
            internet,
            generation,
        })
    }
}

impl Drop for NetworkContextReader {
    fn drop(&mut self) {
        if self.com_initialized {
            unsafe { CoUninitialize() };
        }
    }
}

fn classify_categories(categories: &[Option<NLM_NETWORK_CATEGORY>]) -> NetworkTrust {
    if categories.is_empty() || categories.iter().any(Option::is_none) {
        return NetworkTrust::Unknown;
    }

    let has_public = categories
        .iter()
        .flatten()
        .any(|category| *category == NLM_NETWORK_CATEGORY_PUBLIC);
    let has_trusted = categories.iter().flatten().any(|category| {
        *category == NLM_NETWORK_CATEGORY_PRIVATE
            || *category == NLM_NETWORK_CATEGORY_DOMAIN_AUTHENTICATED
    });
    let has_other = categories.iter().flatten().any(|category| {
        *category != NLM_NETWORK_CATEGORY_PUBLIC
            && *category != NLM_NETWORK_CATEGORY_PRIVATE
            && *category != NLM_NETWORK_CATEGORY_DOMAIN_AUTHENTICATED
    });

    match (has_public, has_trusted, has_other) {
        (true, false, false) => NetworkTrust::Untrusted,
        (false, true, false) => NetworkTrust::Trusted,
        _ => NetworkTrust::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn public_networks_are_untrusted() {
        assert_eq!(
            classify_categories(&[Some(NLM_NETWORK_CATEGORY_PUBLIC)]),
            NetworkTrust::Untrusted
        );
    }

    #[test]
    fn private_and_domain_networks_are_trusted() {
        assert_eq!(
            classify_categories(&[
                Some(NLM_NETWORK_CATEGORY_PRIVATE),
                Some(NLM_NETWORK_CATEGORY_DOMAIN_AUTHENTICATED),
            ]),
            NetworkTrust::Trusted
        );
    }

    #[test]
    fn mixed_or_incomplete_network_sets_are_unknown() {
        assert_eq!(
            classify_categories(&[
                Some(NLM_NETWORK_CATEGORY_PUBLIC),
                Some(NLM_NETWORK_CATEGORY_PRIVATE),
            ]),
            NetworkTrust::Unknown
        );
        assert_eq!(classify_categories(&[None]), NetworkTrust::Unknown);
        assert_eq!(classify_categories(&[]), NetworkTrust::Unknown);
    }
}
