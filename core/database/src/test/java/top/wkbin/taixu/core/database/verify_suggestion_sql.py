"""Targeted real SQLite source-SQL checks. Not a replacement for Room KSP/JUnit."""
import json
import re
import sqlite3
from pathlib import Path

root = next(p for p in Path(__file__).resolve().parents if (p / 'settings.gradle.kts').exists())
base = root / 'core/database'
src = base / 'src/main/java/top/wkbin/taixu/core/database'
schema = json.loads((base / 'schemas/top.wkbin.taixu.core.database.AppDatabase/86.json').read_text())
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
for entity in schema['database']['entities']:
    db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))

def seed(table, values):
    values = dict(values)
    for _, name, typ, required, default, _ in db.execute(f'PRAGMA table_info({table})'):
        if name not in values and required and default is None:
            values[name] = 1 if typ == 'INTEGER' else ''
    db.execute(f'INSERT INTO {table} ({",".join(values)}) VALUES ({",".join("?" for _ in values)})', tuple(values.values()))

seed('persona_bots', {'id': 'bot', 'sessionId': 'bot-dm'})
seed('group_chats', {'id': 'g', 'adminBotId': 'bot'})
seed('group_chats', {'id': 'other', 'adminBotId': 'bot'})
migration = (src / 'TeamSuggestionMigration.kt').read_text()
ddl = re.findall(r'db.execSQL\("""(.*?)"""\)', migration)
ddl += re.findall(r'db.execSQL\("(CREATE INDEX.*?)"\)', migration)
ddl += re.findall(r'db.execSQL\("(CREATE UNIQUE INDEX.*?)"\)', migration)
assert len(ddl) == 6
for statement in ddl:
    db.execute(statement)
assert db.execute('SELECT COUNT(*) FROM team_group_owners').fetchone()[0] == 0
assert db.execute('SELECT COUNT(*) FROM group_chats').fetchone()[0] == 2
seed('team_group_owners', {'groupId': 'g', 'ownerSessionId': 'owner'})
seed('team_group_owners', {'groupId': 'other', 'ownerSessionId': 'stranger'})
seed('team_root_owners', {'rootWorkId': 'root', 'groupId': 'g', 'ownerSessionId': 'owner'})
row = dict(id='s1', groupId='g', sourceAssignmentId='native-task', sourceSessionId='member',
           idempotencyKey='key', reason='secret', operationJson='{"op":"stop","groupId":"g"}',
           beforeSnapshot='snapshot', createdAt=1, status='pending',
           sourceRevision=7, sourceTeamBinding='team@1', sourceMembershipStamp='membership')
seed('team_suggestions', row)
try:
    seed('team_suggestions', dict(row, id='duplicate'))
    raise AssertionError('idempotency uniqueness lost')
except sqlite3.IntegrityError:
    pass
source = (src / 'TeamSuggestionDao.kt').read_text()
queries = re.findall(r'@Query\("([^\n]*)"\)', source)
lookup = next(q for q in queries if q.startswith('SELECT * FROM team_suggestions WHERE id='))
listing = next(q for q in queries if 'ORDER BY createdAt DESC' in q)
update = next(q for q in queries if q.startswith('UPDATE team_suggestions'))

def params(session='owner', group='g', **extra):
    return dict(sessionId=session, groupId=group, id='s1', status='adopted', proposalId='owner-proposal', now=2, sourceRevision=7, sourceTeamBinding='team@1', sourceMembershipStamp='membership', **extra)

for session, group, visible in [('owner', 'g', True), ('member', 'g', True),
                                ('member2', 'g', False), ('stranger', 'g', False),
                                ('owner', 'other', False), ('stranger', 'other', False)]:
    assert bool(db.execute(lookup, params(session, group)).fetchall()) == visible
    assert bool(db.execute(listing, params(session, group)).fetchall()) == visible
for session, group in [('member', 'g'), ('stranger', 'g'), ('owner', 'other')]:
    assert db.execute(update, params(session, group)).rowcount == 0
for field, stale in [('sourceRevision', 8), ('sourceTeamBinding', 'team@2'), ('sourceMembershipStamp', 'rejoined')]:
    assert db.execute(update, dict(params(), **{field: stale})).rowcount == 0
assert db.execute(update, params()).rowcount == 1
assert db.execute(update, params()).rowcount == 0
assert db.execute('SELECT status,proposalId,decidedBy FROM team_suggestions').fetchone() == ('adopted', 'owner-proposal', 'owner')
for statement in ddl:
    db.execute(statement)
assert db.execute('SELECT COUNT(*) FROM team_suggestions').fetchone()[0] == 1
assert not db.execute('PRAGMA foreign_key_check').fetchall()
db.execute("DELETE FROM group_chats WHERE id='g'")
assert db.execute('SELECT COUNT(*) FROM team_suggestions').fetchone()[0] == 0
assert db.execute('SELECT COUNT(*) FROM team_root_owners').fetchone()[0] == 0
print('PASS: v86 -> v87 SQL, legacy fail-closed ownership, FK cascade, scoped payload visibility, idempotency uniqueness, owner-only one-shot adoption and immutable source fingerprint CAS')
