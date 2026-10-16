# 用法：在项目根目录执行  . .\script\dev-env.ps1
# 作用：把本地安装的 JDK 21 与 Maven 加入当前会话 PATH（不改系统/用户环境变量）
$javaHome = (Get-ChildItem D:\tools\jdk21 -Directory -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
$mavenHome = (Get-ChildItem D:\tools\maven -Directory -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
if (-not $javaHome -or -not $mavenHome) {
    Write-Warning '未找到 D:\tools\jdk21 或 D:\tools\maven，请先安装工具链'
    return
}
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$mavenHome\bin;$env:Path"
Write-Host "JAVA_HOME = $javaHome"
Write-Host "Maven     = $mavenHome"
