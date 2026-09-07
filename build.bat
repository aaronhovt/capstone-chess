@echo off
setlocal enabledelayedexpansion

rem Compiles the engine and packages the runnable jars. Run "build" for every target or
rem "build <target>" for one of uci, gui, perft, tactical, eval, bench, book, match,
rem compare, all, or clean.

set "ROOT=%~dp0"
cd /d "%ROOT%"

set "RELEASE=25"
set "OUT=out"
set "JAR_CACHE=jars"
set "WORKTREE=%OUT%\compare-worktree"
set "UCI_JAR=capstone-chess-uci.jar"
set "GUI_JAR=CapstoneChess.jar"
set "UCI_MAIN=engine.forUCI.UciEngine"
set "GUI_MAIN=engine.engineDriver"
set "MATCH_MAIN=engine.forTesting.SelfPlayMatch"
set "ENGINE_LIBS=lib\guava-33.4.0-jre.jar lib\failureaccess-1.0.2.jar"
set "CONT=  "

set "JDK_BIN="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jar.exe" set "JDK_BIN=%JAVA_HOME%\bin\"
if not defined JDK_BIN (
    for /f "delims=" %%P in ('where javac 2^>nul') do (
        if not defined JDK_BIN if exist "%%~dpPjar.exe" set "JDK_BIN=%%~dpP"
    )
)
if not defined JDK_BIN (
    for /d %%D in ("%ProgramFiles%\Java\jdk-*") do (
        if exist "%%~fD\bin\jar.exe" set "JDK_BIN=%%~fD\bin\"
    )
)
if not defined JDK_BIN (
    echo No JDK was found. Set JAVA_HOME to a JDK %RELEASE% installation and run this again.
    exit /b 1
)
echo Using the JDK at !JDK_BIN!

for %%J in (%ENGINE_LIBS%) do (
    if not exist "%%J" (
        echo Missing dependency: %%J
        exit /b 1
    )
)

set "ENGINE_CP="
for %%J in (%ENGINE_LIBS%) do (
    if defined ENGINE_CP (set "ENGINE_CP=!ENGINE_CP!;%%J") else (set "ENGINE_CP=%%J")
)

set "TARGET=%~1"
if not defined TARGET set "TARGET=all"

if /i "%TARGET%"=="all" goto :allTarget
if /i "%TARGET%"=="uci" goto :uciTarget
if /i "%TARGET%"=="gui" goto :guiTarget
if /i "%TARGET%"=="perft" goto :perftTarget
if /i "%TARGET%"=="tactical" goto :tacticalTarget
if /i "%TARGET%"=="eval" goto :evalTarget
if /i "%TARGET%"=="bench" goto :benchTarget
if /i "%TARGET%"=="book" goto :bookTarget
if /i "%TARGET%"=="match" goto :matchTarget
if /i "%TARGET%"=="compare" goto :compareTarget
if /i "%TARGET%"=="clean" goto :cleanTarget
echo Unknown target: %TARGET%
goto :usage

:allTarget
call :buildUci || exit /b 1
call :buildGui || exit /b 1
call :buildSuite "perft" "engine.forTesting.PerftSuite" "src\engine\forTesting\PerftSuite.java" || exit /b 1
call :buildSuite "tactical" "engine.forTesting.TacticalSuite" "src\engine\forTesting\TacticalSuite.java" || exit /b 1
call :buildSuite "eval" "engine.forTesting.EvaluationSuite" "src\engine\forTesting\EvaluationSuite.java" || exit /b 1
call :buildSuite "bench" "engine.forTesting.EvaluationBench" "src\engine\forTesting\EvaluationBench.java" || exit /b 1
call :buildSuite "book" "engine.forTesting.OpeningBook" "src\engine\forTesting\OpeningBook.java" || exit /b 1
call :buildSuite "match" "%MATCH_MAIN%" "src\engine\forTesting\SelfPlayMatch.java" || exit /b 1
goto :done

:uciTarget
call :buildUci || exit /b 1
goto :done

:guiTarget
call :buildGui || exit /b 1
goto :done

:perftTarget
call :buildSuite "perft" "engine.forTesting.PerftSuite" "src\engine\forTesting\PerftSuite.java" || exit /b 1
goto :done

:tacticalTarget
call :buildSuite "tactical" "engine.forTesting.TacticalSuite" "src\engine\forTesting\TacticalSuite.java" || exit /b 1
goto :done

:evalTarget
call :buildSuite "eval" "engine.forTesting.EvaluationSuite" "src\engine\forTesting\EvaluationSuite.java" || exit /b 1
goto :done

:benchTarget
call :buildSuite "bench" "engine.forTesting.EvaluationBench" "src\engine\forTesting\EvaluationBench.java" || exit /b 1
goto :done

:bookTarget
call :buildSuite "book" "engine.forTesting.OpeningBook" "src\engine\forTesting\OpeningBook.java" || exit /b 1
goto :done

:matchTarget
call :buildSuite "match" "%MATCH_MAIN%" "src\engine\forTesting\SelfPlayMatch.java" || exit /b 1
goto :done

:cleanTarget
if exist "%OUT%" rmdir /s /q "%OUT%"
if exist "%UCI_JAR%" del /q "%UCI_JAR%"
if exist "%GUI_JAR%" del /q "%GUI_JAR%"
echo Removed the build output. The jars in %JAR_CACHE% were left alone.
goto :done

:done
endlocal
exit /b 0

:usage
echo Usage: build [target]
echo.
echo   uci        compile the UCI engine and package %UCI_JAR%
echo   gui        compile the interface and package %GUI_JAR%
echo   perft      compile PerftSuite
echo   tactical   compile TacticalSuite
echo   eval       compile EvaluationSuite
echo   bench      compile EvaluationBench
echo   book       compile OpeningBook
echo   match      compile SelfPlayMatch
echo   compare    play one revision against another, see "build compare" for its arguments
echo   all        every target above except compare, which is the default
echo   clean      remove %OUT% and both jars, leaving %JAR_CACHE%
exit /b 1

:compareUsage
echo Usage: build compare ^<first-ref^> [second-ref] [match options]
echo.
echo A ref is anything git resolves to a commit, such as a hash, a tag, a branch, or
echo HEAD~1. The first ref is engine A and the second is engine B. The word working
echo stands for the working tree and can be given in either position, so
echo "build compare working HEAD~1" plays an uncommitted change as engine A. Leaving
echo the second ref out plays the working tree as engine B. An argument beginning
echo with two dashes ends the refs, so "build compare HEAD~1 --pairs 500" reads
echo HEAD~1 as the first ref.
echo.
echo The figures and the sequential test are reported for engine A.
echo.
echo Every option after the refs is passed to %MATCH_MAIN% unchanged. Run that class
echo with --help for the options it takes.
echo.
echo The jar built from a revision is kept in %JAR_CACHE% under its hash and reused
echo the next time that revision is asked for. Delete the jar to force a rebuild.
echo Only revisions holding the uci target can be built this way.
exit /b 1

:compareTarget
shift
set "BASE_REF=%~1"
if not defined BASE_REF goto :compareUsage
if "!BASE_REF:~0,2!"=="--" goto :compareUsage
shift

set "TEST_REF="
if "%~1"=="" goto :compareCollect
set "NEXT_ARG=%~1"
if "!NEXT_ARG:~0,2!"=="--" goto :compareCollect
set "TEST_REF=%~1"
shift

:compareCollect
set "MATCH_ARGS="
:compareCollectLoop
if "%~1"=="" goto :compareStart
set "MATCH_ARGS=!MATCH_ARGS! "%~1""
shift
goto :compareCollectLoop

:compareStart
where git >nul 2>&1
if errorlevel 1 (
    echo No git was found on the path. The compare target checks a revision out to build it.
    exit /b 1
)
set "JAVA_LINE="
for /f "delims=" %%V in ('java -version 2^>^&1') do (
    if not defined JAVA_LINE set "JAVA_LINE=%%V"
)
if not defined JAVA_LINE (
    echo No java was found on the path. The engine processes are started with "java".
    exit /b 1
)
if not exist "%JAR_CACHE%" mkdir "%JAR_CACHE%"

call :buildSide "!BASE_REF!"
if errorlevel 1 exit /b 1
set "BASE_JAR=!SIDE_JAR!"
set "BASE_LABEL=!SIDE_LABEL!"

if not defined TEST_REF set "TEST_REF=working"
call :buildSide "!TEST_REF!"
if errorlevel 1 exit /b 1
set "TEST_JAR=!SIDE_JAR!"
set "TEST_LABEL=!SIDE_LABEL!"

call :buildSuite "match" "%MATCH_MAIN%" "src\engine\forTesting\SelfPlayMatch.java" || exit /b 1
echo [compare] engine A is !BASE_LABEL!
echo [compare] engine B is !TEST_LABEL!
echo [compare] the engines run under !JAVA_LINE!
java -cp "%OUT%\match;!ENGINE_CP!" %MATCH_MAIN% --engine-a "java -jar !BASE_JAR!" --engine-b "java -jar !TEST_JAR!" !MATCH_ARGS!
if errorlevel 1 exit /b 1
goto :done

:buildSide
rem %1 is a revision or the word working. Sets SIDE_JAR to the jar the side plays and
rem SIDE_LABEL to the name it is reported under.
if /i "%~1"=="working" goto :buildSideWorkingTree
call :buildRevision "%~1"
if errorlevel 1 exit /b 1
set "SIDE_JAR=!REV_JAR!"
set "SIDE_LABEL=!REV_SHA:~0,12!"
exit /b 0

:buildSideWorkingTree
call :buildUci || exit /b 1
set "SIDE_JAR=%UCI_JAR%"
set "SIDE_LABEL=working tree"
exit /b 0

:buildRevision
rem %1 is the revision to build. Sets REV_SHA to its hash and REV_JAR to the jar built from it.
set "REV_SHA="
for /f "delims=" %%S in ('git rev-list -n 1 "%~1" 2^>nul') do set "REV_SHA=%%S"
if not defined REV_SHA (
    echo Could not resolve %~1 to a commit.
    exit /b 1
)
set "REV_JAR=%JAR_CACHE%\!REV_SHA!.jar"
if exist "!REV_JAR!" (
    echo [compare] reusing the jar built from !REV_SHA:~0,12!
    exit /b 0
)
echo [compare] building !REV_SHA:~0,12!
if not exist "%OUT%" mkdir "%OUT%"
call :removeWorktree
git worktree add --detach "%WORKTREE%" !REV_SHA!
if errorlevel 1 (
    echo Could not check !REV_SHA:~0,12! out into a worktree.
    exit /b 1
)
pushd "%WORKTREE%" || exit /b 1
call build.bat uci
set "REV_STATUS=!errorlevel!"
popd
if not "!REV_STATUS!"=="0" (
    call :removeWorktree
    echo The uci target failed in !REV_SHA:~0,12!. A revision older than that target cannot be built this way.
    exit /b 1
)
copy /y "%WORKTREE%\%UCI_JAR%" "!REV_JAR!" >nul
if errorlevel 1 (
    call :removeWorktree
    echo Could not copy the jar out of the worktree.
    exit /b 1
)
call :removeWorktree
exit /b 0

:removeWorktree
git worktree remove --force "%WORKTREE%" >nul 2>&1
if exist "%WORKTREE%" rmdir /s /q "%WORKTREE%"
git worktree prune >nul 2>&1
exit /b 0

:compile
rem %1 is the output subdirectory, %2 is the entry point source file.
if exist "%OUT%\%~1" rmdir /s /q "%OUT%\%~1"
mkdir "%OUT%\%~1"
"%JDK_BIN%javac" --release %RELEASE% -nowarn -cp "lib\*" -sourcepath src -d "%OUT%\%~1" "%~2"
if errorlevel 1 (
    echo Compilation of %~2 failed. This build targets Java %RELEASE%, so javac must report that version or later.
    exit /b 1
)
exit /b 0

:buildUci
echo [uci] compiling
call :compile "uci" "src\engine\forUCI\UciEngine.java" || exit /b 1
echo [uci] unpacking dependencies
pushd "%OUT%\uci" || exit /b 1
for %%J in (%ENGINE_LIBS%) do (
    "%JDK_BIN%jar" xf "%ROOT%%%J"
    if errorlevel 1 (
        popd
        echo Unpacking %%J failed.
        exit /b 1
    )
)
popd
if exist "%OUT%\uci\META-INF" rmdir /s /q "%OUT%\uci\META-INF"
echo [uci] packaging %UCI_JAR%
"%JDK_BIN%jar" cfe "%UCI_JAR%" %UCI_MAIN% -C "%OUT%\uci" .
if errorlevel 1 exit /b 1
exit /b 0

:buildGui
echo [gui] compiling
call :compile "gui" "src\engine\engineDriver.java" || exit /b 1
echo [gui] writing the manifest
set "MANIFEST=%OUT%\manifest-gui.txt"
> "%MANIFEST%" echo Main-Class: %GUI_MAIN%
set "FIRST=1"
for %%J in (lib\*.jar) do (
    set "NAME=%%~nJ"
    set "SKIP="
    if "!NAME:~-8!"=="-sources" set "SKIP=1"
    if "!NAME:~-8!"=="-javadoc" set "SKIP=1"
    if not defined SKIP (
        if defined FIRST (
            >> "%MANIFEST%" echo Class-Path: lib/%%~nxJ
            set "FIRST="
        ) else (
            >> "%MANIFEST%" echo !CONT!lib/%%~nxJ
        )
    )
)
echo [gui] packaging %GUI_JAR%
"%JDK_BIN%jar" cfm "%GUI_JAR%" "%MANIFEST%" -C "%OUT%\gui" .
if errorlevel 1 exit /b 1
exit /b 0

:buildSuite
rem %1 is the output subdirectory, %2 is the main class, %3 is the entry point source file.
echo [%~1] compiling
call :compile "%~1" "%~3" || exit /b 1
echo [%~1] run from this directory with: java -cp "%OUT%\%~1;%ENGINE_CP%" %~2
exit /b 0