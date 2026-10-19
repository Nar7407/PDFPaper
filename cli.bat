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
if /I "%~1"=="create" goto createpdf
if /I "%~1"=="images-to-pdf" goto createpdf
if /I "%~1"=="text-to-pdf" goto createpdf
if /I "%~1"=="comments" goto comments
if /I "%~1"=="stamp" goto stamp
if /I "%~1"=="signatures" goto signatures
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

:createpdf
call "%~dp0gradlew.bat" -q :engine:create --args="%*"
exit /b %ERRORLEVEL%

:comments
call "%~dp0gradlew.bat" -q :engine:comments --args="%*"
exit /b %ERRORLEVEL%

:stamp
call "%~dp0gradlew.bat" -q :engine:stamp --args="%*"
exit /b %ERRORLEVEL%

:signatures
call "%~dp0gradlew.bat" -q :signatures:verify --args="%*"
exit /b %ERRORLEVEL%

:usage
echo Usage: cli fontinfo ^<file.pdf^> --page ^<n^> 1>&2
echo        cli add-text^|add-image^|list-images^|move-image^|replace-image^|delete-image ... 1>&2
echo        cli convert pdf-to-pptx ^<in.pdf^> --out ^<out.pptx^> [--fidelity] 1>&2
echo        cli convert pdf-to-docx ^<in.pdf^> --out ^<out.docx^> [--mode flow^|exact] 1>&2
echo        cli convert docx-to-pdf ^<in.docx^> --out ^<out.pdf^> 1>&2
echo        cli convert pptx-to-pdf ^<in.pptx^> --out ^<out.pdf^> 1>&2
echo        cli create blank --size A4 --out ^<out.pdf^> 1>&2
echo        cli images-to-pdf ^<image^>... --out ^<out.pdf^> [--fit fit^|fill^|original] 1>&2
echo        cli text-to-pdf ^<in.txt^> --out ^<out.pdf^> 1>&2
echo        cli comments list^|add^|reply^|set-status ... 1>&2
echo        cli stamp list^|add ... 1>&2
echo        cli signatures verify ^<file.pdf^> [--truststore ^<pem-or-keystore^>] [--password ^<pw^>] 1>&2
exit /b 2
