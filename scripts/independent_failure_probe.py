#!/usr/bin/env python3
"""Controlled table-unavailability probes against disposable H2 only."""
import argparse,hashlib,http.client,json,pathlib,shutil,subprocess,tempfile,time,zipfile
parser=argparse.ArgumentParser();parser.add_argument("--jar",required=True);parser.add_argument("--h2-jar");parser.add_argument("--java",default="java");parser.add_argument("--port",type=int,default=18089);options=parser.parse_args()
root=pathlib.Path(tempfile.mkdtemp(prefix='linkledger-independent-failure-'));java=options.java;db='jdbc:h2:file:'+str(root/'failuredb')+';MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH'
shutil.copy2(options.jar,root/'linkledger.jar')
if options.h2_jar is None:
    with zipfile.ZipFile(root/'linkledger.jar') as archive:
        candidates=[name for name in archive.namelist() if name.startswith('BOOT-INF/lib/h2-') and name.endswith('.jar')]
        assert len(candidates)==1,'Expected one bundled H2 driver'
        embedded_driver=root/'h2-driver.jar'
        embedded_driver.write_bytes(archive.read(candidates[0]))
        options.h2_jar=str(embedded_driver)

print('REVIEWED_JAR_SHA256',hashlib.sha256((root/'linkledger.jar').read_bytes()).hexdigest(),flush=True)
args=[java,'-jar',str(root/'linkledger.jar'),'--server.port='+str(options.port),'--app.base-url=http://localhost:'+str(options.port),'--app.api-key=independent-review-secret','--spring.datasource.url='+db]
def req(method,path,body=None):
    c=http.client.HTTPConnection('127.0.0.1',options.port,timeout=5);c.request(method,path,json.dumps(body) if body else None,{'X-API-Key':'independent-review-secret','Content-Type':'application/json'});r=c.getresponse();out=(r.status,dict(r.getheaders()),r.read().decode());c.close();return out
def start(n):
    log=open(root/('failure-server-'+str(n)+'.log'),'w');p=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT)
    for _ in range(300):
        if p.poll() is not None:raise RuntimeError('app exited')
        try:
            if req('GET','/actuator/health')[0]==200:return p,log
        except OSError:pass
        time.sleep(.1)
    raise RuntimeError('readiness timeout')
def stop(p,log):p.terminate();p.wait(timeout=15);log.close()
def sql(statement):
    r=subprocess.run([java,'-cp',options.h2_jar,'org.h2.tools.Shell','-url',db,'-user','sa','-password','','-sql',statement],capture_output=True,text=True);assert r.returncode==0 and 'Error' not in r.stdout,(r.stdout,r.stderr)
p=log=None
try:
    p,log=start(1);r=req('POST','/api/v1/urls',{'url':'https://example.com/private-token?token=review-fake#secret'});assert r[0]==201,r;l=json.loads(r[2]);stop(p,log);p=log=None
    sql('ALTER TABLE link_metrics RENAME TO review_metrics_unavailable')
    p,log=start(2);r=req('GET','/'+l['code']);assert r[0]==302 and r[1]['Location']==l['url'],r
    r=req('GET','/actuator/metrics/linkledger.analytics.failures');assert r[0]==200 and json.loads(r[2])['measurements'][0]['value']==1,r
    r=req('GET','/api/v1/urls/'+l['code']+'/analytics');assert r[0]==503 and 'Storage is temporarily unavailable' in r[2] and 'SELECT' not in r[2],r
    print('PASS real missing metrics table: redirect302, analytics failure metric1, statistics503 with sanitized error',flush=True)
    stop(p,log);p=log=None;sql('ALTER TABLE review_metrics_unavailable RENAME TO link_metrics')
    sql('ALTER TABLE links RENAME TO review_links_unavailable')
    p,log=start(3);r=req('GET','/'+l['code']);assert r[0]==503 and 'Storage is temporarily unavailable' in r[2],r
    print('PASS real missing authoritative link table: redirect503 rather than invented destination',flush=True)
    stop(p,log);p=log=None;sql('ALTER TABLE review_links_unavailable RENAME TO links')
    for f in root.glob('failure-server-*.log'):
        logtext=f.read_text();assert 'review-fake' not in logtext and 'independent-review-secret' not in logtext,f
    print('PASS failure logs omit synthetic destination query token and API key',flush=True)
finally:
    if p is not None:stop(p,log)
