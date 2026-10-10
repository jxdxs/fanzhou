# Fanzhou build notes (updated 2026-10-07, v2.0.0)

## Toolchain (sandbox is now x86_64, not aarch64)
- /workspace/tools/jdk-17.0.20.1+1 and build-tools aapt2/zipalign are aarch64 binaries and do NOT run here.
- Added x86_64 JDK: /workspace/tools/jdk17-x64 ; x86_64 aapt2: /workspace/tools/aapt2-x64/aapt2 (from Google Maven aapt2 8.7.3-12006047 linux).
- apksigner works (java jar) with JAVA_HOME=/workspace/tools/jdk17-x64.
- Every shell command runs in a 1.5 GB memory cgroup (processes it spawns, including Gradle daemons, stay in it).
  Gradle with -Xmx3g gets OOM-killed. Use work/build.sh: --no-daemon, max-workers=1, -Xmx760m, SerialGC,
  in-process Kotlin compiler, aapt2 override on the command line (gradle.properties still points to the arm64 aapt2).
- Run: nohup setsid work/build.sh assembleRelease &  then poll /tmp/build.log for "EXIT n" (~2-4 min).
- If a full build OOMs, run compileReleaseKotlin first in one shell call, then assembleRelease in another.

## Dependency notes
- media3 is 1.7.1 (not 1.6.1): org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+2 (and +1) depend on media3 1.7.1.
- dev.jdtech.mpv:libmpv:1.0.0: AAR requires compileSdk 36 (check disabled in app/build.gradle.kts) and is compiled with
  Kotlin 2.2 (kotlin stdlib excluded; -Xskip-metadata-version-check). MPVLib is instance-based: MPVLib.create(ctx)?.
- com.hierynomus:smbj:0.14.0 with BCSecurityProvider + NTLM-only authenticator.
