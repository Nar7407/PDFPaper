@echo off
setlocal
if "%~1"=="" goto usage
if /I "%~1"=="fontinfo" goto fontinfo
if /I "%~1"=="add-text" goto pdfedit
if /I "%~1"=="add-image" goto pdfedit
if /I "%~1"=="list-images" goto pdfedit
if /I "%~1"=="move-image" goto pdfedit
if /I "%~1"=="replace-image" goto pdfedit
if /I "%~1"=="delete-image" goto pdfedit
if /I "%~1"=="convert" goto convert
goto usage

:fontinfo
if not "%~3"=="--page" goto usage
call "%~dp0gradlew.bat" -q :engine:fontinfo -PfontinfoFile="%~2" -PfontinfoPage="%~4"
exit /b %ERRORLEVEL%

:pdfedit
call "%~dp0gradlew.bat" -q :engine:pdfedit --args="%*"
exit /b %ERRORLEVEL%

:convert
call "%~dp0gradlew.bat" -q :convert:convert --args="%*"
exit /b %ERRORLEVEL%

:usage
echo Usage: cli fontinfo ^<file.pdf^> --page ^<n^> 1>&2
echo        cli add-text^|add-image^|list-images^|move-image^|replace-image^|delete-image ... 1>&2
echo        cli convert pdf-to-pptx ^<in.pdf^> --out ^<out.pptx^> [--fidelity] 1>&2
echo        cli convert pdf-to-docx ^<in.pdf^> --out ^<out.docx^> [--mode flow^|exact] 1>&2
echo        cli convert docx-to-pdf ^<in.docx^> --out ^<out.pdf^> 1>&2
exit /b 2
