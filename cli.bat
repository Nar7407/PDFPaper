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
if /I "%~1"=="sign-visual" goto signvisual
if /I "%~1"=="sign" goto sign
if /I "%~1"=="signatures" goto signatures
if /I "%~1"=="ocr" goto ocr
if /I "%~1"=="compress" goto compress
if /I "%~1"=="mark" goto mark
if /I "%~1"=="export-images" goto exportimages
if /I "%~1"=="extract-images" goto exportimages
if /I "%~1"=="crop" goto crop
if /I "%~1"=="search-regex" goto searchregex
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

:signvisual
set "SIGNVISUAL_ARGS=%*"
set "SIGNVISUAL_ARGS=%SIGNVISUAL_ARGS:"=%"
call "%~dp0gradlew.bat" -q :engine:signvisual --args="%SIGNVISUAL_ARGS%"
exit /b %ERRORLEVEL%

:sign
call "%~dp0gradlew.bat" -q :signatures:sign --args="%*"
exit /b %ERRORLEVEL%

:signatures
call "%~dp0gradlew.bat" -q :signatures:verify --args="%*"
exit /b %ERRORLEVEL%

:ocr
call "%~dp0gradlew.bat" -q :engine:ocr --args="%*"
exit /b %ERRORLEVEL%

rem The compress case (JVM host codec + CLI). The comment also keeps the
rem label off cmd.exe's 1024-byte chunk boundary: a "goto" whose label is
rem exactly 1024 bytes away is silently not found.
:compress
call "%~dp0gradlew.bat" -q :render-jvm:compress --args="%*"
exit /b %ERRORLEVEL%

rem The mark case (watermarks, page numbers, Bates). The quotes are stripped
rem so the arguments survive the batch file.
:mark
set "MARK_ARGS=%*"
set "MARK_ARGS=%MARK_ARGS:"=%"
call "%~dp0gradlew.bat" -q :engine:mark --args="%MARK_ARGS%"
exit /b %ERRORLEVEL%

:exportimages
call "%~dp0gradlew.bat" -q :render-jvm:export --args="%*"
exit /b %ERRORLEVEL%

rem T46 crop and auto-crop (JVM host rasterizer for detection).
:crop
call "%~dp0gradlew.bat" -q :render-jvm:crop --args="%*"
exit /b %ERRORLEVEL%

rem T65 regex search: one pattern, one PDF, a CSV of every match.
:searchregex
call "%~dp0gradlew.bat" -q :engine:search-regex --args="%*"
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
echo        cli sign-visual ^<in.pdf^> --page N --image^|--typed^|--strokes ... --x X --y Y --width W --out ^<out.pdf^> 1>&2
echo        cli sign ^<in.pdf^> --p12 ^<key.p12^> --pass-env ^<ENV^> [--rect l,b,r,t] --out ^<out.pdf^> 1>&2
echo        cli signatures verify ^<file.pdf^> [--truststore ^<pem-or-keystore^>] [--password ^<pw^>] 1>&2
echo        cli ocr ^<file.pdf^> [--lang eng+hin] [--out ^<out.pdf^>] [--pages 1,3-5] [--text ^<words^>] 1>&2
echo        cli compress ^<in.pdf^> --preset ^<light^|balanced^|strong^> --out ^<out.pdf^> [--dpi ^<n^>] [--quality ^<n^>] [--grayscale] 1>&2
echo        cli mark watermark^|number-pages^|bates ^<in.pdf^> --out ^<out.pdf^> [...] 1>&2
echo        cli export-images ^<in.pdf^> --out-dir ^<dir^> [--dpi 150] [--pages 1-5] [--format png^|jpg^|webp] 1>&2
echo        cli extract-images ^<in.pdf^> --out-dir ^<dir^> [--min-size 32] [--list] 1>&2
echo        cli crop ^<in.pdf^> --margins 10,10,10,10 --pages all --out ^<out.pdf^> [--remove-hidden] 1>&2
echo        cli search-regex ^<in.pdf^> "^<pattern^>" --out ^<matches.csv^> [--case-sensitive] [--lines] [--pages 1,3-5] 1>&2
exit /b 2
