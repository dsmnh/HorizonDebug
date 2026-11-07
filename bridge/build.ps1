$ErrorActionPreference = "Stop"

# 构建依赖两个环境变量（本机路径不入仓库，取值见工作区文档 测试环境.md）：
#   BC_API_JAR  —— BungeeCord/HexaCord API jar（同时提供 shaded Gson）
#   JAVA_HOME   —— Java 8 JDK（zulu8）
$api = $env:BC_API_JAR
if (-not $api) {
  throw "BC_API_JAR not set: path of the BungeeCord/HexaCord API jar"
}
if (-not (Test-Path -LiteralPath $api)) {
  throw "BC API jar not found: $api"
}
if (-not $env:JAVA_HOME) {
  throw "JAVA_HOME not set: point it at a Java 8 JDK"
}

if (Test-Path -LiteralPath "build") { Remove-Item -LiteralPath "build" -Recurse -Force }
New-Item -ItemType Directory -Force build\classes | Out-Null

$ErrorActionPreference = "Continue"
& "$env:JAVA_HOME\bin\javac.exe" -source 8 -target 8 -encoding UTF-8 -cp $api `
  -d build\classes src\main\java\dev\dsmnh\horizon\debug\bridge\*.java 2>&1 | Out-Null
$code = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($code -ne 0) { throw "javac failed" }

& "$env:JAVA_HOME\bin\jar.exe" cf HorizonDebugBridge.jar -C build\classes . -C src\main\resources .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Output "built: HorizonDebugBridge.jar"
