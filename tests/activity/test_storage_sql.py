"""Host SQLite integration tests of the exact production SQL (not the Android/native adapter)."""
from pathlib import Path
import re, sqlite3, tempfile, unittest
SOURCE=Path(__file__).resolve().parents[2]/'TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageStorageSql.java'
SQL=dict(re.findall(r'public static final String (\w+) = "([^"]+)";',SOURCE.read_text()))
class StorageSqlTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(prefix='activity-sql-')
  self.path=Path(self.tmp.name)/'usage.db'
  self.db=sqlite3.connect(self.path)
  self.db.execute('PRAGMA journal_mode=WAL')
  for key in ['META','BUCKET','INDEX','DAILY','SENT']: self.db.execute(SQL[key])
  self.db.commit()
 def tearDown(self): self.db.close(); self.tmp.cleanup()
 def credit(self,day=20261005,hour=12,account=11,surface=1,dialog=123,seconds=60):
  self.db.execute(SQL['UPSERT_BUCKET'],(day,hour,account,surface,dialog,seconds))
 def total(self): return self.db.execute('SELECT COALESCE(SUM(seconds),0) FROM bucket').fetchone()[0]
 def test_upsert_and_reopen(self):
  self.credit(seconds=30); self.credit(seconds=40); self.db.commit(); self.db.close()
  self.db=sqlite3.connect(self.path)
  self.assertEqual(70,self.total()); self.assertEqual(1,self.db.execute('SELECT COUNT(*) FROM bucket').fetchone()[0])
 def test_rollup_existing_daily_row_and_replay(self):
  self.credit(20260101,1,seconds=31); self.credit(20260101,2,seconds=32); self.credit(20260101,-1,seconds=33); self.credit(20261005,12,seconds=34)
  for _ in range(2):
   self.db.execute(SQL['ROLLUP'],(20260707,)); self.db.execute(SQL['DELETE_HOURS'],(20260707,))
  self.assertEqual(130,self.total())
  self.assertEqual([(96,)],self.db.execute('SELECT seconds FROM bucket WHERE hour=-1').fetchall())
 def test_fold_uses_whole_dialog_day_and_preserves_categories(self):
  self.credit(20230101,-1,surface=1,dialog=1,seconds=20); self.credit(20230101,-1,surface=2,dialog=1,seconds=39)
  self.credit(20230101,-1,dialog=2,seconds=60); self.credit(20230101,-1,dialog=0,seconds=5)
  self.credit(20250101,1,dialog=3,seconds=1)
  self.db.execute(SQL['FOLD'],(20241005,20241005)); self.db.execute(SQL['DELETE_FOLDED'],(20241005,20241005))
  self.assertEqual(125,self.total()); self.assertEqual([],self.db.execute('SELECT dialog FROM bucket WHERE dialog=1').fetchall())
  self.assertEqual([(60,)],self.db.execute('SELECT seconds FROM bucket WHERE dialog=2').fetchall())
  self.assertEqual([(25,),(39,)],self.db.execute('SELECT seconds FROM bucket WHERE day=20230101 AND dialog=0 ORDER BY surface').fetchall())
 def test_anonymization_is_account_isolated_and_idempotent(self):
  self.credit(account=11,seconds=10); self.credit(account=11,dialog=0,seconds=5); self.credit(account=22,seconds=20)
  for _ in range(2):
   self.db.execute(SQL['ANONYMIZE'],(11,)); self.db.execute(SQL['DELETE_IDENTITIES'],(11,))
  self.assertEqual(35,self.total())
  self.assertEqual([(11,0,15),(22,123,20)],self.db.execute('SELECT account,dialog,seconds FROM bucket ORDER BY account').fetchall())
 def test_late_delivery_after_logout_preserves_anonymity_and_other_account(self):
  self.credit(account=11,seconds=10); self.credit(account=22,seconds=20)
  self.db.execute(SQL['ANONYMIZE'],(11,)); self.db.execute(SQL['DELETE_IDENTITIES'],(11,)); self.db.commit()
  self.confirm(11,'late-success',20261005)
  self.credit(account=11,seconds=2) # unsettled old-owner credit in the late boundary
  self.db.execute(SQL['ANONYMIZE'],(11,)); self.db.execute(SQL['DELETE_IDENTITIES'],(11,)); self.db.commit()
  self.confirm(11,'late-success',20261005)
  self.assertEqual([(11,0,12),(22,123,20)],self.db.execute('SELECT account,dialog,seconds FROM bucket ORDER BY account').fetchall())
  self.assertEqual((1,),self.db.execute('SELECT messages_sent FROM daily WHERE account=11').fetchone())
 def test_migration_merge_does_not_drop_or_duplicate(self):
  self.credit(dialog=-1,surface=5,seconds=10); self.credit(dialog=-2,surface=5,seconds=20)
  for _ in range(2):
   self.db.execute(SQL['MIGRATE'],(-2,11,-1)); self.db.execute(SQL['DELETE_MIGRATED'],(11,-1))
  self.assertEqual(30,self.total()); self.assertEqual([(-2,30)],self.db.execute('SELECT dialog,seconds FROM bucket').fetchall())
 def confirm(self,account,token,day):
  if not self.db.execute(SQL['HAS_SENT'],(account,token)).fetchone():
   self.db.execute(SQL['INSERT_SENT'],(account,token)); self.db.execute(SQL['SENT_METRIC'],(day,account))
 def test_confirmations_survive_restart_and_replay_once(self):
  self.confirm(11,'opaque',20261005); self.db.commit(); self.db.close(); self.db=sqlite3.connect(self.path)
  self.confirm(11,'opaque',20261006); self.confirm(22,'opaque',20261005); self.confirm(11,'other',20261005)
  self.assertEqual([(11,2),(22,1)],self.db.execute('SELECT account,messages_sent FROM daily ORDER BY account').fetchall())
 def test_clock_moving_back_does_not_suppress_verified_confirmations(self):
  self.db.execute("INSERT INTO meta VALUES('created_at',?)",(9999999999999,))
  self.confirm(11,'new-confirmation',20261005)
  self.confirm(11,'new-confirmation',20261004) # same identity replay after a clock jump
  self.confirm(11,'second-confirmation',20261004)
  self.assertEqual([(20261004,1),(20261005,1)],self.db.execute('SELECT day,messages_sent FROM daily ORDER BY day').fetchall())
 def test_failed_transaction_then_retry_is_atomic(self):
  self.db.execute('BEGIN IMMEDIATE'); self.credit(seconds=40); self.confirm(11,'opaque',20261005)
  self.db.rollback()
  self.assertEqual(0,self.total()); self.assertEqual([],self.db.execute('SELECT * FROM sent').fetchall())
  self.db.execute('BEGIN IMMEDIATE'); self.credit(seconds=40); self.confirm(11,'opaque',20261005); self.db.commit()
  self.confirm(11,'opaque',20261005)
  self.assertEqual(40,self.total()); self.assertEqual((1,),self.db.execute('SELECT messages_sent FROM daily').fetchone())
 def test_daily_max_not_sum_and_reset_all_content(self):
  self.db.execute(SQL['UPSERT_DAILY'],(20261005,11,1,1,40,0)); self.db.execute(SQL['UPSERT_DAILY'],(20261005,11,0,0,60,0))
  self.confirm(11,'opaque',20261005); self.credit()
  self.assertEqual((1,1,60,1),self.db.execute('SELECT opens,sessions,longest_session,messages_sent FROM daily').fetchone())
  for table in ['bucket','daily','sent','meta']: self.db.execute('DELETE FROM '+table)
  for table in ['bucket','daily','sent','meta']: self.assertEqual((0,),self.db.execute('SELECT COUNT(*) FROM '+table).fetchone())
 def test_wal_reader_sees_only_committed_flush(self):
  reader=sqlite3.connect(self.path)
  try:
   self.db.execute('BEGIN IMMEDIATE'); self.credit(seconds=40)
   self.assertEqual((0,),reader.execute('SELECT COUNT(*) FROM bucket').fetchone())
   self.db.commit()
   self.assertEqual((40,),reader.execute('SELECT SUM(seconds) FROM bucket').fetchone())
  finally: reader.close()
 def test_range_query_does_not_include_other_period(self):
  self.credit(20261004,seconds=10); self.credit(20261005,seconds=20); self.credit(20261006,seconds=30)
  self.assertEqual(1,len(self.db.execute(SQL['QUERY_BUCKET'],(20261005,20261005)).fetchall()))
if __name__=='__main__': unittest.main(verbosity=2)
