"""Host-only retention size/query sample; not an Android performance or disk-size guarantee."""
from pathlib import Path
import hashlib,sqlite3,tempfile,time,datetime,runpy
base=Path(__file__).resolve().parent
sql=runpy.run_path(str(base/'test_storage_sql.py'))['SQL']
with tempfile.TemporaryDirectory(prefix='activity-size-') as tmp:
 db=sqlite3.connect(Path(tmp)/'usage.db')
 for key in ['META','BUCKET','INDEX','DAILY','SENT']:db.execute(sql[key])
 today=datetime.date(2026,10,5)
 day=lambda d:d.year*10000+d.month*100+d.day
 hourly=[];old=[]
 for age in range(5*365):
  d=day(today-datetime.timedelta(days=age))
  if age<=90:
   # Heavy profile: 300 distinct hourly rows/day, 40 dialogs plus non-dialog surfaces.
   for row in range(300):hourly.append((d,row//40,11,5,-(row%40+1),60))
  else:
   for dialog in range(40):old.append((d,-1,11,5,-dialog-1,600))
 db.executemany(sql['UPSERT_BUCKET'],hourly+old);db.commit()
 size=(Path(tmp)/'usage.db').stat().st_size
 t=time.perf_counter()
 rows=db.execute(sql['QUERY_BUCKET'],(20261001,20261031)).fetchall()
 elapsed=(time.perf_counter()-t)*1000
 print(f'Host heavy 5-year bucket sample: {size} bytes; current-month {len(rows)} rows read in {elapsed:.2f} ms')
 # Idempotency adds space independently of the category/dialog retention estimate.
 sent=[(11,hashlib.sha256(str(i).encode()).hexdigest()) for i in range(100*5*365)]
 db.executemany(sql['INSERT_SENT'],sent);db.commit()
 print(f'With 100 confirmations/day for 5 years: {(Path(tmp)/"usage.db").stat().st_size} bytes')
 db.close()
