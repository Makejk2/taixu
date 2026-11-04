"""SQLite smoke check of source SQL against exported v85; NOT a Room/KSP test."""
import json
import re
import sqlite3
from pathlib import Path

root = next(p for p in Path(__file__).resolve().parents if (p / 'settings.gradle.kts').exists())
base = root / 'core/database'
source = (base / 'src/main/java/top/wkbin/taixu/core/database/TeamCommunicationDao.kt').read_text()
schema = json.loads((base / 'schemas/top.wkbin.taixu.core.database.AppDatabase/85.json').read_text())
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
for entity in schema['database']['entities']:
    db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
for ddl in re.findall(r'db.execSQL\("""(CREATE TABLE.*?)"""\)', source):
    db.execute(ddl)
for ddl in re.findall(r'db.execSQL\("(CREATE (?:UNIQUE )?INDEX.*?)"\)', source):
    db.execute(ddl)

def seed(table, values):
    # Required fields without an SQL default need explicit fixture values.
    for _, name, typ, required, default, pk in db.execute(f'PRAGMA table_info({table})'):
        if pk and typ == 'INTEGER':
            continue
        if name not in values and required and default is None:
            values[name] = 1 if typ == 'INTEGER' else ''
    db.execute(f'INSERT INTO {table} ({",".join(values)}) VALUES ({",".join("?" for _ in values)})', tuple(values.values()))

seed('persona_bots', {'id': 'a', 'sessionId': 'dm-a'})
seed('group_chats', {'id': 'g', 'adminBotId': 'a'})
seed('harness_sessions', {'id': 's'})
seed('bot_assignments', {'id': 'target', 'groupId': 'g', 'botId': 'a', 'status': 'RUNNING', 'sessionId': 's', 'independent': 1, 'teamBinding': 'team@1'})
statuses = ['pending', 'running', 'completed', 'admitted', 'cancelled']
for n, status in enumerate(statuses):
    seed('bot_assignment_requests', {'id': f'peer:{5-n}', 'assignmentId': 'target', 'createdAt': 100-n,
         'instruction': json.dumps({'sourceAssignmentId': 'source', 'sourceMemberId': 'a', 'hop': 1}), 'status': status})
original = list(db.execute('SELECT * FROM bot_assignment_requests ORDER BY rowid'))
select = re.search(r'db.query\("(SELECT r.id.*?)"\)', source).group(1)
policy = re.search(r'db.execSQL\("(INSERT OR IGNORE INTO team_communication_policy.*?)"', source).group(1)
message = re.search(r'db.execSQL\("(INSERT OR IGNORE INTO team_communication_messages.*?)"', source).group(1)
for _ in range(2):
    for row in db.execute(select).fetchall():
        id, target, instruction, created, group, binding, seq, status, task_status, session = row
        env = json.loads(instruction)
        state = 'cancelled' if status in ('cancelled', 'failed', 'interrupted') or task_status in ('CANCELLED','FAILED','INTERRUPTED') or not db.execute('SELECT 1 FROM harness_sessions WHERE id=?', (session,)).fetchone() else 'queued'
        db.execute(policy, (group, '{}', created))
        db.execute(message, (id, group, binding, env['sourceAssignmentId'], env['sourceMemberId'], target, instruction, env['hop'], created, state, created, seq))
    assert list(db.execute('SELECT * FROM bot_assignment_requests ORDER BY rowid')) == original
    assert db.execute('SELECT COUNT(*) FROM team_communication_messages WHERE chargedAt IS NOT NULL').fetchone()[0] == 5
rows = db.execute('SELECT id,deliverySequence,state FROM team_communication_messages ORDER BY deliverySequence').fetchall()
assert rows == [(f'peer:{5-n}', n+1, 'cancelled' if n==4 else 'queued') for n in range(5)]
assert not db.execute('PRAGMA foreign_key_check').fetchall()
# New Kotlin constructor defaults intentionally have NO @ColumnInfo defaults, so SQL must match.
assert all(r[4] is None for r in db.execute('PRAGMA table_info(team_communication_messages)'))
assert all(r[4] is None for r in db.execute('PRAGMA table_info(team_communication_policy)'))
# Delivery cursor allocates beyond imported rowid; releases do not use old hold creation sequence.
update = re.search(r'@Query\("(UPDATE team_communication_messages SET state=:state.*?)"\)', source).group(1)
seed('team_communication_messages', {'id':'held', 'groupId':'g','sourceAssignmentId':'source','sourceMemberId':'a',
     'targetAssignmentId':'target','instruction':'held','hop':1,'state':'held'})
db.execute(update, {'state':'queued','reason':None,'chargedAt':200,'id':'held'})
assert db.execute("SELECT deliverySequence FROM team_communication_messages WHERE id='held'").fetchone()[0] == 6
inbox = re.search(r'@Query\("(SELECT \* FROM team_communication_messages WHERE targetAssignmentId.*?)"\)', source).group(1)
assert db.execute(inbox, {'target':'target','after':5,'limit':1}).fetchone()[1] == 'held'
print('PASS: v85 actual DDL + v86 source SQL, 5 legacy statuses, ordering, repeat import, no replay/recharge, FK/defaults, late release cursor')

# Exercise the production identity and session-deletion SQL, not a paraphrased query.
native = (base / 'src/main/java/top/wkbin/taixu/core/database/NativeTeamStartDao.kt').read_text()
count_annotation = native[:native.index('suspend fun nativeTeamSourceBotCount')].rsplit('@Query(', 1)[1]
identity_sql = ''.join(re.findall(r'"([^"\n]*)"', count_annotation))
seed('bot_group_sessions', {'botId':'a','groupId':'g','sessionId':'group-a'})
for session, expected in [('owner',0),('dm-a',1),('group-a',1),('s',1)]:
    assert db.execute(identity_sql, {'sessionId':session}).fetchone()[0] == expected
session_source = (base / 'src/main/java/top/wkbin/taixu/core/database/HarnessSessionEntity.kt').read_text()
cancel_annotation = session_source[:session_source.index('suspend fun cancelCommunicationsForSession')].rsplit('@Query(', 1)[1]
cancel_sql = ''.join(re.findall(r'"([^"\n]*)"', cancel_annotation))
db.execute(cancel_sql, {'id':'s'})
assert db.execute("SELECT COUNT(*) FROM team_communication_messages WHERE state IN ('held','queued')").fetchone()[0] == 0
print('PASS: native identity SQL rejects direct/group/assignment sessions; session deletion terminalizes ledger')
