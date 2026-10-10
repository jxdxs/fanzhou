#!/bin/bash
# x86_64 sandbox build helper (each shell command runs in a 1.5 GB memory cgroup, so keep the JVM small)
export JAVA_HOME=/workspace/tools/jdk17-x64 GRADLE_USER_HOME=/workspace/tools/gradle-home
export PATH=$JAVA_HOME/bin:$PATH
cd /workspace/projects/081aa918-6bb1-4725-9ea6-2028a0db24bd/FanzhouPlayer
/workspace/tools/gradle-8.9/bin/gradle --no-daemon --max-workers=1 \
  "-Dorg.gradle.jvmargs=-Xmx760m -XX:MaxMetaspaceSize=320m -XX:CICompilerCount=2 -Xss512k -XX:+UseSerialGC -XX:ReservedCodeCacheSize=128m -Dfile.encoding=UTF-8" \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pandroid.aapt2FromMavenOverride=/workspace/tools/aapt2-x64/aapt2 ${@:-assembleRelease} -q > /tmp/build.log 2>&1
echo "EXIT $?" >> /tmp/build.log
