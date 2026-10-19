import sqlite3
import os
import sys

# 用法: disable_module.py <数据库路径>
# 把环境变量 PKG 指定的模块在 modules_state 里的 enabled 置 0（保留 user_version），
# 用于"暂时禁用"某个 LSPosed 模块而不卸载它。
#
# 本文件放在合并项目 tools/lspfix/ 下，是 E:\WorkSpace\lspfix\disable_module.py 的副本，
# 只是把写死的包名换成 PKG 环境变量。

db = sys.argv[1]
pkg = os.environ.get('PKG', 'com.pixelos.lockscreenisland')

c = sqlite3.connect(db)
uv_before = c.execute('PRAGMA user_version').fetchone()[0]
integ_before = c.execute('PRAGMA integrity_check').fetchone()[0]

cur = c.execute("UPDATE modules_state SET enabled=0 WHERE module_pkg_name=?", (pkg,))
c.commit()

uv_after = c.execute('PRAGMA user_version').fetchone()[0]
if uv_after != uv_before:
    c.execute('PRAGMA user_version=%d' % uv_before)
    c.commit()

print('db            =', db)
print('pkg           =', pkg)
print('rows updated  =', cur.rowcount)
print('before        = uv=%s integrity=%s' % (uv_before, integ_before))
print('after         = uv=%s integrity=%s' % (
    c.execute('PRAGMA user_version').fetchone()[0],
    c.execute('PRAGMA integrity_check').fetchone()[0]))
print('state         =', list(c.execute(
    "SELECT * FROM modules_state WHERE module_pkg_name=?", (pkg,))))
print('modules total =', c.execute('SELECT COUNT(*) FROM modules').fetchone()[0])
c.close()
