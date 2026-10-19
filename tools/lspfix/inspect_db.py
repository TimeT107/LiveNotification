import sqlite3, sys

db = sys.argv[1]
c = sqlite3.connect(db)
print('tables:', [r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")])
print('scope schema:', [r for r in c.execute('PRAGMA table_info(scope)')])
print('--- scope rows ---')
for r in c.execute("SELECT * FROM scope"):
    print(r)
print('--- modules ---')
for r in c.execute("SELECT * FROM modules"):
    print(r)
c.close()
