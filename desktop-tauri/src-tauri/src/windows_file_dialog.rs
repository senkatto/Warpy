use windows::{
    core::{w, HRESULT},
    Win32::{
        System::Com::*,
        UI::Shell::{Common::COMDLG_FILTERSPEC, *},
    },
};

pub(crate) fn select_executable() -> Result<Option<String>, String> {
    unsafe {
        CoInitializeEx(None, COINIT_APARTMENTTHREADED)
            .ok()
            .map_err(|e| e.to_string())?;
        struct Apartment;
        impl Drop for Apartment {
            fn drop(&mut self) {
                unsafe {
                    CoUninitialize();
                }
            }
        }
        let _apartment = Apartment;
        let dialog: IFileOpenDialog = CoCreateInstance(&FileOpenDialog, None, CLSCTX_INPROC_SERVER)
            .map_err(|e| e.to_string())?;
        dialog
            .SetOptions(
                FOS_FILEMUSTEXIST
                    | FOS_PATHMUSTEXIST
                    | FOS_FORCEFILESYSTEM
                    | FOS_DONTADDTORECENT
                    | FOS_STRICTFILETYPES,
            )
            .map_err(|e| e.to_string())?;
        dialog
            .SetFileTypes(&[COMDLG_FILTERSPEC {
                pszName: w!("Applications (*.exe)"),
                pszSpec: w!("*.exe"),
            }])
            .map_err(|e| e.to_string())?;
        dialog
            .SetDefaultExtension(w!("exe"))
            .map_err(|e| e.to_string())?;
        dialog
            .SetTitle(w!("Выберите программу"))
            .map_err(|e| e.to_string())?;
        if let Ok(folder) =
            SHCreateItemFromParsingName::<_, _, IShellItem>(w!("C:\\Program Files"), None)
        {
            let _ = dialog.SetFolder(&folder);
        }
        match dialog.Show(None) {
            Ok(()) => {
                let item = dialog.GetResult().map_err(|e| e.to_string())?;
                let path = item
                    .GetDisplayName(SIGDN_FILESYSPATH)
                    .map_err(|e| e.to_string())?;
                let result = path.to_string();
                CoTaskMemFree(Some(path.0.cast()));
                result.map(Some).map_err(|e| e.to_string())
            }
            Err(error) if error.code() == HRESULT::from_win32(1223) => Ok(None),
            Err(error) => Err(error.to_string()),
        }
    }
}
