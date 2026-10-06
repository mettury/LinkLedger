#!/usr/bin/env python3
import argparse,hashlib,http.client,json,pathlib,shutil,subprocess,tempfile,time,sys
parser=argparse.ArgumentParser();parser.add_argument('--jar',required=True);parser.add_argument('--java',default='java');parser.add_argument('--port',type=int,default=18089);options=parser.parse_args()
ROOT=pathlib.Path(tempfile.mkdtemp(prefix='linkledger-independent-'));PORT=options.port
shutil.copy2(options.jar,ROOT/'linkledger.jar')
print('REVIEWED_JAR_SHA256',hashlib.sha256((ROOT/'linkledger.jar').read_bytes()).hexdigest(),flush=True)
args=[options.java,'-jar',str(ROOT/'linkledger.jar'),'--server.port='+str(PORT),'--app.base-url=http://localhost:'+str(PORT),'--app.api-key=independent-review-secret','--app.create-limit-per-minute=100000','--spring.datasource.url=jdbc:h2:file:'+str(ROOT/'reviewdb')+';MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH']
def request(method,path,body=None,chunked=False):
    c=http.client.HTTPConnection('127.0.0.1',PORT,timeout=5);headers={'X-API-Key':'independent-review-secret','Content-Type':'application/json'}
    if isinstance(body,dict):body=json.dumps(body)
    if chunked:body=iter([body.encode()])
    c.request(method,path,body,headers,encode_chunked=chunked);r=c.getresponse();b=r.read().decode();v=(r.status,dict(r.getheaders()),b);c.close();return v
def start(logname):
    log=open(ROOT/logname,'w');p=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT)
    for i in range(300):
        if p.poll() is not None:raise RuntimeError('Server failed: '+(ROOT/logname).read_text()[-5000:])
        try:
            if request('GET','/actuator/health')[0]==200:return p,log
        except (OSError,http.client.HTTPException):pass
        time.sleep(.1)
    p.terminate();p.wait(timeout=10);raise RuntimeError('Server readiness timeout')
def stop(p,log):
    p.terminate();p.wait(timeout=15);log.close()
p=log=None
try:
    p,log=start('server-first.log')
    result=subprocess.run([sys.executable,str(pathlib.Path(__file__).with_name('independent_http_probe.py')),'--port',str(PORT)])
    print('BLACK_BOX_RETURN_CODE',result.returncode,flush=True);assert result.returncode==0,'Black-box assertions failed'
    for label,body in [('trailing-json','{"url":"https://example.com/first"}{"url":"https://example.com/second"}'),('trailing-garbage','{"url":"https://example.com/first"} garbage'),('out-of-range-expiry',{'url':'https://example.com/expiry','expiresAt':'+1000000000-12-31T23:59:59.999999999Z'})]:
        r=request('POST','/api/v1/urls',body);assert r[0]==400,(label,r);print('PASS',label,'rejected400',flush=True)
    r=request('POST','/api/v1/urls',' '*9000+'{"url":"https://example.com/chunked"}',chunked=True);assert r[0]==413,r;print('PASS chunked whitespace oversized body rejected413',flush=True)
    r=request('POST','/api/v1/urls',{'url':'https://example.com/persistent?meaning=42#route'});assert r[0]==201,r;l=json.loads(r[2]);assert request('GET','/'+l['code'])[0]==302
    stop(p,log);p=log=None
    p,log=start('server-restart.log')
    r=request('GET','/'+l['code']);assert r[0]==302 and r[1]['Location']==l['url'],r
    r=request('GET','/api/v1/urls/'+l['code']+'/analytics');assert json.loads(r[2])['totalRedirects']==2,r
    print('PASS H2 file restart preserves destination and counter; Flyway validates existing schema',flush=True)
finally:
    if p is not None:stop(p,log)
