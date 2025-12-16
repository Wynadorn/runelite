set "JAVA_EXE=C:\Program Files (vm)\Java\jdk\jdk-11.0.22+7\bin\javaw.exe"
set "USER_HOME=C:\Src\repos\runelite\debug-profile"
set "JAR=C:\Src\repos\runelite\runelite-client\target\client-1.12.9-SNAPSHOT-shaded.jar"

if not exist "%JAR%" (
	echo [WARN] Shaded JAR not found at %JAR%, falling back to non-shaded jar
	set "JAR=C:\Src\repos\runelite\runelite-client\target\client-1.12.9-SNAPSHOT.jar"
)

if not exist "%JAR%" (
	echo [ERROR] JAR not found: %JAR%
	echo Build it with: mvn -DskipTests -pl runelite-client -am package
	exit /b 1
)

start "" "%JAVA_EXE%" -Xmx2G -ea "-Drunelite.pluginhub.version=1.12.8" "-Duser.home=%USER_HOME%" "-Drunelite.debug.ui=true" -jar "%JAR%" %*