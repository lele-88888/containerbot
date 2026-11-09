# Container Bot - one-click build + install
#
# Run this in a NORMAL PowerShell (NOT inside the DSH sandbox).
#
# Why it must run outside the sandbox:
#   The DSH sandbox confines child processes with a restricted token plus a low
#   integrity label, and grants write access through a logon-session SID
#   (S-1-4-...), bypassing normal DACL evaluation. As a result Java's
#   Files.isWritable() returns false for every path. The JDK's
#   jdk.nio.zipfs contains:
#       this.readOnly = ... || !Files.isWritable(zfpath);
#   so every jar/zip is forced read-only and Fabric Loom fails while writing
#   mappings with ReadOnlyFileSystemException.
#
#   Ruled out by testing: JDK version (21 and 25 behave the same), integrity
#   level (Medium makes no difference), ACL (removing the Everyone DENY makes
#   no difference), location (workspace and TEMP both fail), reparse points.
#
# This file is intentionally ASCII-only: PowerShell 5.1 decodes BOM-less files
# using the system ANSI codepage, which would corrupt any non-ASCII text here
# and break string parsing.

$ErrorActionPreference = 'Continue'

# ---------------------------------------------------------------------------
# Robust project-root resolution.
#
# $PSScriptRoot can be empty or wrong depending on how the script is launched
# (and we have seen it resolve to the user's home directory, which produced
# "Directory 'C:\Users\HUAWEI' does not contain a Gradle build").
# So: try $PSScriptRoot, then the invocation path, then a hard-coded absolute
# fallback - and finally validate that settings.gradle is really there.
# ---------------------------------------------------------------------------
$knownRoot = 'D:\HuaweiMoveData\Users\HUAWEI\Documents\deepseek-harness\default-workspace\containerbot'

$root = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($root)) {
    try { $root = Split-Path -Parent $MyInvocation.MyCommand.Path } catch { $root = '' }
}

$resolved = $false
foreach ($candidate in @($root, $knownRoot)) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    if (Test-Path (Join-Path $candidate 'settings.gradle')) {
        if ($candidate -ne $root) {
            Write-Host "NOTE: project not found under '$root'; using '$candidate' instead." -ForegroundColor Yellow
        }
        $root = $candidate
        $resolved = $true
        break
    }
}

if (-not $resolved) {
    Write-Host "ERROR: cannot locate the Gradle project (no settings.gradle found)." -ForegroundColor Red
    Write-Host "  tried: $root" -ForegroundColor Red
    Write-Host "  tried: $knownRoot" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Set-Location -LiteralPath $root

$env:JAVA_HOME        = "$root\tools\jdk-dist\jdk-25.0.4.1+1"
$env:GRADLE_USER_HOME = "$root\.gradle-home"
$gradle               = "$root\tools\gradle-dist\gradle-9.7.0\bin\gradle.bat"
$log                  = "$root\build-output.log"
$modsDir              = "D:\.minecraft\versions\1.21.8\mods"
$libsDir              = "$root\build\libs"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Container Bot - build" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  project (cwd)    : $root"
Write-Host "  settings.gradle  : $(Test-Path (Join-Path $root 'settings.gradle'))"
Write-Host "  JAVA_HOME        : $env:JAVA_HOME"
Write-Host "  GRADLE_USER_HOME : $env:GRADLE_USER_HOME"
Write-Host "  full log         : $log"
Write-Host ""

if (-not (Test-Path $env:JAVA_HOME)) {
    Write-Host "ERROR: JDK 25 not found -> $env:JAVA_HOME" -ForegroundColor Red
    Read-Host "Press Enter to exit"; exit 1
}
if (-not (Test-Path $gradle)) {
    Write-Host "ERROR: Gradle not found -> $gradle" -ForegroundColor Red
    Read-Host "Press Enter to exit"; exit 1
}

# Stop any leftover Gradle daemon so cache locks cannot conflict.
& $gradle --stop 2>&1 | Out-Null

$allOut    = New-Object System.Collections.Generic.List[string]
$succeeded = $false
$failCode  = -1

for ($i = 1; $i -le 15; $i++) {
    Write-Host ""
    Write-Host "############ attempt $i / 15 ############" -ForegroundColor Yellow
    [void]$allOut.Add("")
    [void]$allOut.Add("############ attempt $i / 15 ############")

    $sw = [Diagnostics.Stopwatch]::StartNew()

    # Capture manually: Tee-Object CANNOT combine -FilePath with -Variable
    # (different parameter sets - that bug once reported a false success).
    & $gradle -p $root build --console=plain 2>&1 | ForEach-Object {
        if ($_ -is [System.Management.Automation.ErrorRecord]) {
            $line = $_.Exception.Message
        } else {
            $line = "$_"
        }
        Write-Host $line
        [void]$allOut.Add($line)
    }
    $code = $LASTEXITCODE
    $sw.Stop()
    $failCode = $code

    $msg = "--- attempt {0} finished: exit={1} elapsed={2:N1} min ---" -f $i, $code, $sw.Elapsed.TotalMinutes
    Write-Host $msg
    [void]$allOut.Add($msg)

    # Success requires BOTH a zero exit code AND an actual artifact directory.
    if ($code -eq 0 -and (Test-Path $libsDir)) { $succeeded = $true; break }

    # Dependency downloads may fail because the cross-border link is flaky.
    # Already-downloaded artifacts stay in the cache, so retrying converges.
    Write-Host "--- failed, retrying in 5s (downloaded dependencies are kept) ---" -ForegroundColor DarkYellow
    Start-Sleep -Seconds 5
}

# Persist the whole transcript for later inspection.
$allOut | Set-Content -Path $log -Encoding UTF8

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Cyan

if ($succeeded) {
    Write-Host " BUILD SUCCESSFUL" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Cyan

    $jar = Get-ChildItem $libsDir -Filter "*.jar" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch "sources|javadoc" } |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1

    if ($jar) {
        Write-Host ""
        Write-Host "artifact: $($jar.Name)  ($([math]::Round($jar.Length/1KB,1)) KB)" -ForegroundColor Cyan

        if (Test-Path $modsDir) {
            Copy-Item $jar.FullName -Destination $modsDir -Force
            Write-Host "installed to:" -ForegroundColor Green
            Write-Host "  $modsDir\$($jar.Name)" -ForegroundColor Green
        } else {
            Write-Host "mods dir not found, please copy manually to $modsDir" -ForegroundColor Yellow
        }

        Write-Host ""
        Write-Host "Next: launch the 1.21.8 world, then use these commands (needs OP / cheats):" -ForegroundColor Cyan
        Write-Host "  /cbot spawn Bot                              spawn the fake player" -ForegroundColor White
        Write-Host "  /cbot start Bot minecraft:diamond 64         collect 64 diamonds" -ForegroundColor White
        Write-Host "  /cbot start Bot minecraft:iron_ingot 128 48  collect with radius 48" -ForegroundColor White
        Write-Host "  /cbot status                                 show progress" -ForegroundColor White
        Write-Host "  /cbot stop Bot                               stop the task" -ForegroundColor White
    } else {
        Write-Host "Build reported success but no jar found - check build\libs" -ForegroundColor Yellow
    }
} else {
    Write-Host " BUILD FAILED (last exit code: $failCode)" -ForegroundColor Red
    Write-Host "==========================================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "===== ERROR SUMMARY (please copy this part back) =====" -ForegroundColor Yellow

    # Collect interesting lines. Crucially, always include the lines that
    # FOLLOW "What went wrong:" - the actual cause lives there, and a plain
    # Select-String on the header alone hides it (that flaw already forced two
    # incomplete bug reports).
    $errs = New-Object System.Collections.Generic.List[string]
    for ($k = 0; $k -lt $allOut.Count; $k++) {
        $ln = $allOut[$k]
        if ($ln -match "What went wrong") {
            for ($j = $k; $j -le [Math]::Min($k + 3, $allOut.Count - 1); $j++) {
                [void]$errs.Add($allOut[$j])
            }
        }
        elseif ($ln -match "error:|Caused by|Could not resolve|compileJava|warning:|\.java:") {
            [void]$errs.Add($ln)
        }
    }
    if ($errs.Count -gt 0) {
        $errs | Select-Object -First 60 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    } else {
        Write-Host "  (no typical error line matched; sending the tail instead)" -ForegroundColor Red
        $allOut | Select-Object -Last 30 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    }
    Write-Host ""
    Write-Host "full log saved to: $log" -ForegroundColor Yellow
}

Write-Host ""
Read-Host "Press Enter to exit"
