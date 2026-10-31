@echo off
rem Windows: builds the jar and runs the benchmark with a 512 MB heap; writes docs\benchmark-results.md
cd /d "%~dp0\.."
call mvnw.cmd -q -B package -DskipTests || exit /b 1
java -Xmx512m -Dloader.main=com.moveinsync.roster.bench.Benchmark -cp target\hr-roster-sync-1.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher docs\benchmark-results.md
