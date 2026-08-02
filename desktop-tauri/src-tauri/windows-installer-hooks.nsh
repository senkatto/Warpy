!define WARPY_SERVICE_NAME "WarpyVpnService"

Var WarpyHadDesktopShortcut
Var WarpyRollbackReady
Var WarpyRollbackDir
Var WarpyPreviousVersion
Var WarpyHealthMarker
Var WarpyHealthWait
Var WarpyUserDataDir

!macro NSIS_HOOK_PREINSTALL
  StrCpy $WarpyHadDesktopShortcut 0
  StrCpy $WarpyRollbackReady 0
  StrCpy $WarpyRollbackDir "$INSTDIR\.warpy-rollback"
  SetShellVarContext current
  StrCpy $WarpyUserDataDir "$APPDATA\com.warpy.desktop"
  StrCpy $WarpyHealthMarker "$WarpyUserDataDir\launch-health-${VERSION}.ok"
  IfFileExists "$DESKTOP\${PRODUCTNAME}.lnk" 0 +2
    StrCpy $WarpyHadDesktopShortcut 1
  SetShellVarContext all

  ${If} $UpdateMode = 1
    ReadRegStr $WarpyPreviousVersion SHCTX "${UNINSTKEY}" "DisplayVersion"
    Delete "$WarpyHealthMarker"
    RMDir /r "$WarpyRollbackDir"
    CreateDirectory "$WarpyRollbackDir"
    CreateDirectory "$WarpyRollbackDir\bin"
    CreateDirectory "$WarpyRollbackDir\flags"
    CreateDirectory "$WarpyRollbackDir\user-data"

    ClearErrors
    CopyFiles /SILENT "$INSTDIR\${MAINBINARYNAME}.exe" "$WarpyRollbackDir\${MAINBINARYNAME}.exe"
    CopyFiles /SILENT "$INSTDIR\sing-box.exe" "$WarpyRollbackDir\sing-box.exe"
    CopyFiles /SILENT "$INSTDIR\bin\wintun.dll" "$WarpyRollbackDir\bin\wintun.dll"
    CopyFiles /SILENT "$INSTDIR\flags\*.*" "$WarpyRollbackDir\flags"
    ${If} ${FileExists} "$INSTDIR\uninstall.exe"
      CopyFiles /SILENT "$INSTDIR\uninstall.exe" "$WarpyRollbackDir\uninstall.exe"
    ${EndIf}
    ${If} ${FileExists} "$WarpyUserDataDir\settings.dat"
      CopyFiles /SILENT "$WarpyUserDataDir\settings.dat" "$WarpyRollbackDir\user-data\settings.dat"
    ${EndIf}

    IfErrors warpy_backup_failed
    IfFileExists "$WarpyRollbackDir\${MAINBINARYNAME}.exe" 0 warpy_backup_failed
    IfFileExists "$WarpyRollbackDir\sing-box.exe" 0 warpy_backup_failed
    IfFileExists "$WarpyRollbackDir\bin\wintun.dll" 0 warpy_backup_failed
    StrCpy $WarpyRollbackReady 1
    Goto warpy_backup_done

    warpy_backup_failed:
      RMDir /r "$WarpyRollbackDir"
      MessageBox MB_ICONSTOP "Не удалось сохранить предыдущую версию Warpy. Обновление отменено без изменений."
      Abort
    warpy_backup_done:
  ${EndIf}

  IfFileExists "$INSTDIR\${MAINBINARYNAME}.exe" 0 service_binary_missing
    ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --uninstall-service' $0
    ${If} $0 != 0
      RMDir /r "$WarpyRollbackDir"
      MessageBox MB_ICONSTOP "Не удалось подготовить системную службу Warpy к обновлению. Перезагрузите Windows и повторите установку."
      Abort
    ${EndIf}
    Goto service_stopped
  service_binary_missing:
    nsExec::ExecToLog '"$SYSDIR\sc.exe" stop "${WARPY_SERVICE_NAME}"'
    nsExec::ExecToLog '"$SYSDIR\sc.exe" delete "${WARPY_SERVICE_NAME}"'
    Sleep 1000
  service_stopped:
  SetOverwrite on
  Delete "$INSTDIR\sing-box.exe"
  Delete "$INSTDIR\bin\wintun.dll"
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --install-service' $0
  ${If} $0 != 0
    ${If} $WarpyRollbackReady = 1
      Goto warpy_rollback
    ${EndIf}
    MessageBox MB_ICONSTOP "Не удалось установить системную службу Warpy. Установка будет отменена."
    Abort
  ${EndIf}

  SetShellVarContext current
  Delete "$DESKTOP\${PRODUCTNAME}.lnk"
  Delete "$SMPROGRAMS\${PRODUCTNAME}.lnk"
  Delete "$SMPROGRAMS\${PRODUCTNAME}\${PRODUCTNAME}.lnk"
  RMDir "$SMPROGRAMS\${PRODUCTNAME}"
  ${If} $WarpyHadDesktopShortcut = 1
    CreateShortcut "$DESKTOP\${PRODUCTNAME}.lnk" "$INSTDIR\${MAINBINARYNAME}.exe"
  ${EndIf}
  SetShellVarContext all

  RMDir /r "$LOCALAPPDATA\Warpy"
  DeleteRegKey HKCU "${UNINSTKEY}"
  DeleteRegKey HKCU "${MANUPRODUCTKEY}"

  ${If} $WarpyRollbackReady = 1
    Delete "$WarpyHealthMarker"
    nsis_tauri_utils::RunAsUser "$INSTDIR\${MAINBINARYNAME}.exe" "--post-update-health-check"
    StrCpy $WarpyHealthWait 0

    warpy_health_wait:
      IfFileExists "$WarpyHealthMarker" warpy_update_healthy
      Sleep 1000
      IntOp $WarpyHealthWait $WarpyHealthWait + 1
      IntCmp $WarpyHealthWait 30 warpy_rollback warpy_health_wait warpy_rollback

    warpy_update_healthy:
      Delete "$WarpyHealthMarker"
      RMDir /r "$WarpyRollbackDir"
      StrCpy $WarpyRollbackReady 0
      ${GetSize} "$INSTDIR" "/M=uninstall.exe /S=0K /G=0" $0 $1 $2
      IntOp $0 $0 + ${ESTIMATEDSIZE}
      IntFmt $0 "0x%08X" $0
      WriteRegDWORD SHCTX "${UNINSTKEY}" "EstimatedSize" "$0"
      Goto warpy_postinstall_done

    warpy_rollback:
      Delete "$WarpyHealthMarker"
      ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --uninstall-service' $0
      ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --rollback-shutdown' $0
      nsExec::ExecToLog '"$SYSDIR\sc.exe" stop "${WARPY_SERVICE_NAME}"'
      Pop $0
      Sleep 1500
      nsExec::ExecToLog '"$SYSDIR\sc.exe" delete "${WARPY_SERVICE_NAME}"'
      Pop $0
      Sleep 1000

      StrCpy $WarpyHealthWait 0
    warpy_restore_binary:
      ClearErrors
      CopyFiles /SILENT "$WarpyRollbackDir\${MAINBINARYNAME}.exe" "$INSTDIR\${MAINBINARYNAME}.exe"
      IfErrors 0 warpy_restore_files
      Sleep 500
      IntOp $WarpyHealthWait $WarpyHealthWait + 1
      IntCmp $WarpyHealthWait 10 warpy_restore_failed warpy_restore_binary warpy_restore_failed

    warpy_restore_files:
      ClearErrors
      CopyFiles /SILENT "$WarpyRollbackDir\sing-box.exe" "$INSTDIR\sing-box.exe"
      CopyFiles /SILENT "$WarpyRollbackDir\bin\wintun.dll" "$INSTDIR\bin\wintun.dll"
      CopyFiles /SILENT "$WarpyRollbackDir\flags\*.*" "$INSTDIR\flags"
      ${If} ${FileExists} "$WarpyRollbackDir\uninstall.exe"
        CopyFiles /SILENT "$WarpyRollbackDir\uninstall.exe" "$INSTDIR\uninstall.exe"
      ${EndIf}
      ${If} ${FileExists} "$WarpyRollbackDir\user-data\settings.dat"
        CreateDirectory "$WarpyUserDataDir"
        CopyFiles /SILENT "$WarpyRollbackDir\user-data\settings.dat" "$WarpyUserDataDir\settings.dat"
      ${EndIf}
      IfErrors warpy_restore_failed

      ${If} $WarpyPreviousVersion != ""
        WriteRegStr SHCTX "${UNINSTKEY}" "DisplayVersion" "$WarpyPreviousVersion"
      ${EndIf}
      ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --install-service' $0
      ${If} $0 != 0
        Goto warpy_restore_failed
      ${EndIf}

      RMDir /r "$WarpyRollbackDir"
      StrCpy $WarpyRollbackReady 0
      ${GetSize} "$INSTDIR" "/M=uninstall.exe /S=0K /G=0" $0 $1 $2
      IntOp $0 $0 + ${ESTIMATEDSIZE}
      IntFmt $0 "0x%08X" $0
      WriteRegDWORD SHCTX "${UNINSTKEY}" "EstimatedSize" "$0"
      nsis_tauri_utils::RunAsUser "$INSTDIR\${MAINBINARYNAME}.exe" ""
      MessageBox MB_ICONEXCLAMATION "Новая версия Warpy не прошла проверку запуска. Предыдущая рабочая версия восстановлена автоматически."
      SetErrorLevel 1
      Quit

    warpy_restore_failed:
      MessageBox MB_ICONSTOP "Автоматическое восстановление Warpy не завершилось. Перезапустите Windows и установите предыдущую версию вручную."
      SetErrorLevel 2
      Quit
  ${EndIf}

  warpy_postinstall_done:
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  nsExec::ExecToLog '"$SYSDIR\schtasks.exe" /Delete /TN "Warpy" /F'
  Pop $0
  ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --uninstall-service' $0
  ${If} $0 != 0
    MessageBox MB_ICONSTOP "Не удалось остановить системную службу Warpy. Повторите удаление после перезагрузки Windows."
    Abort
  ${EndIf}
!macroend
