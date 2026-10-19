import sqlite3, sys

# 用法: disable_modules.py <db> <pkg1,pkg2,...>
# 把指定模块在 modules_state 里的 enabled 置 0（保留 user_version / 不卸载）。
db = sys.argv[1]
pkgs = [p for p in sys.argv[2].split(',') if p]

c = sqlite3.connect(db)
uv = c.execute('PRAGMA user_version').fetchone()[0]
print('integrity before =', c.execute('PRAGMA integrity_check').fetchone()[0])
for p in pkgs:
    cur = c.execute("UPDATE modules_state SET enabled=0 WHERE module_pkg_name=?", (p,))
    print('disable', p, 'rows =', cur.rowcount)
c.commit()
cur_uv = c.execute('PRAGMA user_version').fetchone()[0]
if cur_uv != uv:
    c.execute('PRAGMA user_version=%d' % uv)
    c.commit()
print('user_version %s -> %s' % (uv, c.execute('PRAGMA user_version').fetchone()[0]))
print('integrity after  =', c.execute('PRAGMA integrity_check').fetchone()[0])
for p in pkgs:
    print('state', p, '=', list(c.execute(
        'SELECT module_pkg_name, enabled FROM modules_state WHERE module_pkg_name=?', (p,))))
c.close()
