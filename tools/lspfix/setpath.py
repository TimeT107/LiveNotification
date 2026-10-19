import sqlite3
import os
import sys

# 用法: setpath.py <数据库路径>
# 把环境变量 NEWP 里的 APK 路径写进 modules 表，并核对库的完整性。
# 包名从环境变量 PKG 取（默认合并后的 com.example.livenotification）。
#
# 本文件是 E:\WorkSpace\lspfix\setpath.py 的副本，唯一改动是把写死的
# com.pixelos.lockscreenisland 换成 PKG。原项目 LockScreenIsland 继续独立更新，
# 不要回改原文件。
#
# 刻意不动 user_version：真机事故证明把它写成 0 会让守护进程
# 报 "table modules already exists" 而崩溃。

db = sys.argv[1] if len(sys.argv) > 1 else r'D:\MyAndroidProject\tools\lspfix\deploy.db'
newp = os.environ['NEWP']
pkg = os.environ.get('PKG', 'com.example.livenotification')

c = sqlite3.connect(db)
uv_before = c.execute('PRAGMA user_version').fetchone()[0]
integ_before = c.execute('PRAGMA integrity_check').fetchone()[0]

c.execute("UPDATE modules SET apk_path=? WHERE module_pkg_name=?", (newp, pkg))
c.commit()

uv_after = c.execute('PRAGMA user_version').fetchone()[0]
integ_after = c.execute('PRAGMA integrity_check').fetchone()[0]

print('db            =', db)
print('pkg           =', pkg)
print('before        = uv=%s integrity=%s' % (uv_before, integ_before))
print('after         = uv=%s integrity=%s' % (uv_after, integ_after))
print('modules total =', c.execute('SELECT COUNT(*) FROM modules').fetchone()[0])
print('scope total   =', c.execute('SELECT COUNT(*) FROM scope').fetchone()[0])
print('ours row      =', list(c.execute(
    "SELECT module_pkg_name, apk_path, enabled FROM modules WHERE module_pkg_name=?", (pkg,))))
if c.execute("SELECT COUNT(*) FROM modules WHERE module_pkg_name=?", (pkg,)).fetchone()[0] == 0:
    print('!! modules 表里还没有本模块的行：请先在 LSPosed 里启用一次，让它注册。')

if uv_after != 101:
    print('!! 警告: user_version 不是 101，守护进程可能崩溃')
if integ_after != 'ok':
    print('!! 警告: 库完整性检查未通过')
c.close()
