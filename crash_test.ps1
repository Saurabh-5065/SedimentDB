# crash_test.ps1 - Simulates kill -9 during writes and verifies crash recovery.
#
# What this does:
#   1. Cleans any prior test data
#   2. Starts CrashTestWriter as a background process, capturing ACK output
#   3. Waits a few seconds for writes to accumulate
#   4. Kills the process hard (taskkill /F, the Windows equivalent of kill -9)
#   5. Parses the last ACK line to find the last durable write index
#   6. Runs CrashTestVerifier to confirm all ACKed writes survived

param(
    [string]$DataDir = "D:\WebDev\SystemProgramming\DistroDB\crash-test-data",
    [int]$WaitSeconds = 5
)

$classpath = "D:\WebDev\SystemProgramming\DistroDB\target\classes"

Write-Host "=========================================="
Write-Host " LSM-Tree Crash Recovery Test"
Write-Host "=========================================="
Write-Host ""

# Step 1: Clean prior data
if (Test-Path $DataDir) {
    Remove-Item -Recurse -Force $DataDir
    Write-Host "[1/6] Cleaned old test data at $DataDir"
} else {
    Write-Host "[1/6] No old test data to clean"
}

$ackFile = "$DataDir-acks.txt"
$errFile = "$DataDir-err.txt"

# Step 2: Start the writer process
Write-Host "[2/6] Starting CrashTestWriter ..."
$proc = Start-Process java `
    -ArgumentList "-cp", $classpath, "lsmdb.core.CrashTestWriter", $DataDir `
    -PassThru `
    -NoNewWindow `
    -RedirectStandardOutput $ackFile `
    -RedirectStandardError $errFile

Write-Host "       Writer PID: $($proc.Id)"

# Step 3: Let it write for a few seconds
Write-Host "[3/6] Waiting $WaitSeconds seconds for writes to accumulate ..."
Start-Sleep -Seconds $WaitSeconds

# Step 4: Kill it hard (like kill -9)
if (-not $proc.HasExited) {
    Write-Host "[4/6] Killing writer process (PID $($proc.Id)) with taskkill /F ..."
    taskkill /F /PID $proc.Id 2>&1 | Out-Null
    Start-Sleep -Milliseconds 500
    Write-Host "       Process killed."
} else {
    Write-Host "[4/6] Process already exited. Try reducing WaitSeconds."
}

# Step 5: Parse the last ACK
Write-Host "[5/6] Parsing last acknowledged write ..."
$lastAck = Get-Content $ackFile -Tail 1
if ($lastAck -match "ACK key-(\d+) = (.+)") {
    $lastIndex = [int]$Matches[1]
    $lastValue = $Matches[2]
    Write-Host "       Last ACK: index=$lastIndex value=$lastValue"
    
    $totalAcks = (Get-Content $ackFile | Measure-Object -Line).Lines
    Write-Host "       Total ACKs: $totalAcks"
} else {
    Write-Host "       ERROR: Could not parse last ACK line."
    Write-Host "       Last line: $lastAck"
    Get-Content $ackFile -Tail 5
    exit 1
}

Write-Host ""

# Step 6: Run the verifier
Write-Host "[6/6] Running CrashTestVerifier ..."
Write-Host ""
& java -cp $classpath lsmdb.core.CrashTestVerifier $DataDir $lastIndex

Write-Host ""
Write-Host "=========================================="
Write-Host " Test complete."
Write-Host "=========================================="
