; Dex-X — NSIS hooks.
;
; WHY THIS FILE EXISTS. The installer was aborting with
;
;     Error opening file for writing:
;     ...\AppData\Local\Dex-X\resources\bin\AdbWinApi.dll
;     ...\AppData\Local\Dex-X\resources\bin\AdbWinUsbApi.dll
;     ...\AppData\Local\Dex-X\resources\bin\adb.exe
;
; Those three are exactly the files a RUNNING adb holds open: the Dex-X window
; keeps an adb server alive for as long as it is open (and adb is spawned per
; command, so it can still be running after the window was closed). NSIS cannot
; overwrite a mapped DLL, so the install stops half way — and a half-installed
; folder is worse than an old one: whatever it did manage to write is new, adb
; and its two DLLs are old, and every symptom after that ("no window daemon",
; "no desktop", "nothing works") looks like ten unrelated bugs.
;
; So both hooks shut the old session down before a single file is touched.
; taskkill's exit code is ignored on purpose: "not running" is the normal case.

!macro DexXStopOldProcesses
  DetailPrint "Closing the running Dex-X, adb and scrcpy…"
  ; The window and its helpers. Both binary names: the app used to ship as
  ; open-android-dex-tauri.exe and now ships as Dex-X.exe, and an upgrade has
  ; to be able to stop the OLD one or it cannot replace it.
  nsExec::Exec 'taskkill /F /T /IM "Dex-X.exe"'
  Pop $0
  nsExec::Exec 'taskkill /F /T /IM "open-android-dex-tauri.exe"'
  Pop $0
  ; adb.exe is the one that actually held the files: the window starts a server
  ; and each command line touches adb.exe again.
  nsExec::Exec 'taskkill /F /IM "adb.exe"'
  Pop $0
  nsExec::Exec 'taskkill /F /IM "scrcpy.exe"'
  Pop $0
  ; A file that is closed but still inside its delete/close window is the one
  ; remaining way the copy fails; a second of slack costs nothing.
  Sleep 800
!macroend

!macro NSIS_HOOK_PREINSTALL
  !insertmacro DexXStopOldProcesses
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  !insertmacro DexXStopOldProcesses
!macroend

; After the files land, tell Explorer that the icon changed. Windows caches
; shell icons per path, so an upgrade otherwise keeps showing the OLD logo on
; the shortcut and the taskbar for days — reported as "desktop shortcut করে
; পুরোনো লোগো আসে" long after the exe itself had the new one.
!macro NSIS_HOOK_POSTINSTALL
  nsExec::Exec 'ie4uinit.exe -show'
  Pop $0
!macroend
