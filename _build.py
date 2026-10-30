"""构建脚本：用 cmd /c 调 gradlew.bat（直接执行 .bat 会 WinError 193）。"""
import subprocess, sys, time, os

ROOT = os.path.dirname(os.path.abspath(__file__))
ENV = dict(os.environ)
# 系统 PATH 里的 java 是 JRE 8，Gradle 8.7 + AGP 必须要 JDK 17，
# 工具链装在 D:\workbuddy\toolchain（隔离目录，不污染系统）
TOOLCHAIN = r"D:\workbuddy\toolchain"
ENV["JAVA_HOME"] = os.path.join(TOOLCHAIN, "jdk17")
ENV["ANDROID_HOME"] = os.path.join(TOOLCHAIN, "android-sdk")
ENV["ANDROID_SDK_ROOT"] = ENV["ANDROID_HOME"]
ENV["GRADLE_USER_HOME"] = r"C:\Users\yuhao\.gradle"
ENV["PATH"] = os.path.join(ENV["JAVA_HOME"], "bin") + os.pathsep + ENV.get("PATH", "")

TASKS = sys.argv[1:] or ["assembleDebug", "assembleRelease"]
rc = 0
for task in TASKS:
    cmd = ["cmd", "/c", "gradlew.bat", "--no-daemon", "--console=plain", task]
    print("=== " + task + " ===", flush=True)
    t0 = time.time()
    p = subprocess.run(cmd, cwd=ROOT, env=ENV,
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       text=True, encoding="utf-8", errors="replace")
    errs = [l for l in p.stdout.splitlines()
            if "error:" in l.lower() or l.strip().startswith("e:") or " FAILED" in l]
    for line in p.stdout.splitlines():
        if ("> Task" in line or "BUILD" in line or "error" in line.lower()
                or line.startswith(("e:", "w:"))):
            print(line[:200], flush=True)
    print("rc=%d  用时 %.0fs" % (p.returncode, time.time() - t0), flush=True)
    for e in errs[:15]:
        print("  ERR " + e.strip()[:200], flush=True)
    rc = rc or p.returncode
    if rc != 0:
        break

print("=== 产物 ===", flush=True)
for flavor in ["debug", "release"]:
    d = os.path.join(ROOT, "app", "build", "outputs", "apk", flavor)
    if os.path.isdir(d):
        for f in sorted(os.listdir(d)):
            if f.endswith(".apk"):
                fp = os.path.join(d, f)
                print("  %s  %.2f MB" % (fp, os.path.getsize(fp) / 1024 / 1024), flush=True)
sys.exit(rc)
