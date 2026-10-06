# Sets up JDK 21 + Maven (IntelliJ bundled) + corporate (Zscaler) truststore for this shell.
# Usage:  . .\scripts\env.ps1
$jdk = "$env:USERPROFILE\.jdks\ms-21.0.11"
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { $env:JAVA_HOME = $jdk }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$mvnHome = "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3"
if (Test-Path "$mvnHome\bin\mvn.cmd") { $env:Path = "$mvnHome\bin;$env:Path" }

$trust = "$env:USERPROFILE\.m2\zscaler-truststore.p12"
if (Test-Path $trust) {
  $opts = "-Djavax.net.ssl.trustStore=`"$trust`" -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"
  $env:MAVEN_OPTS = $opts
  $env:RCP_JAVA_TRUST_OPTS = $opts
}
Write-Host "JAVA_HOME=$env:JAVA_HOME"
