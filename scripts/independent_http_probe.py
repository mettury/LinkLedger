#!/usr/bin/env python3
"""Independent black-box checks. Run only against a disposable LinkLedger instance."""
import argparse, concurrent.futures, datetime, http.client, json, time, uuid
p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=18089);p.add_argument('--key',default='independent-review-secret');a=p.parse_args()
results=[]
def req(method,path,body=None,auth=True,extra=None):
    c=http.client.HTTPConnection('127.0.0.1',a.port,timeout=10)
    headers={'Content-Type':'application/json'}
    if auth:headers['X-API-Key']=a.key
    headers.update(extra or {})
    if isinstance(body,dict):body=json.dumps(body)
    c.request(method,path,body,headers);r=c.getresponse();raw=r.read();out=(r.status,dict((k.lower(),v) for k,v in r.getheaders()),raw.decode('utf-8',errors='replace'));c.close();return out
def obj(r):return json.loads(r[2])
def check(name,fn):
    try:fn();results.append({'name':name,'result':'PASS'});print('PASS',name,flush=True)
    except Exception as e:results.append({'name':name,'result':'FAIL','detail':repr(e)});print('FAIL',name,repr(e),flush=True)
def require(cond,detail):
    if not cond:raise AssertionError(detail)
def create(payload,**kw):
    r=req('POST','/api/v1/urls',payload,**kw);require(r[0]==201,r);return obj(r)
def auth_paths():
    for path in ['/api/v1/urls','/%61pi/v1/urls','/api;ignored/v1/urls','/api%3Bignored/v1/urls','/api%253Bignored/v1/urls','/api/v1;ignored/urls','/api//v1/urls','//api/v1/urls','/x/../api/v1/urls','/api/v1/urls/','/api%2fv1%2furls','/api/v1/urls;.json']:
        r=req('POST',path,{'url':'https://example.com/'},auth=False);require(400<=r[0]<500,(path,r))
    for path in ['/actuator/metrics','/%61ctuator/metrics','/actuator;ignored/metrics','/actuator/env','/actuator/configprops','/api/v1/urls/missing/analytics']:
        r=req('GET',path,auth=False);require(400<=r[0]<500,(path,r))
check('unauthenticated protected routes and normalization variants',auth_paths)
link={}
def preserving():
    target='https://example.com/A%2fb?item=42&item=43&token=a%2Bb&utm_source=email#route/details'
    link.update(create({'url':target},extra={'Host':'attacker.invalid','X-Forwarded-Host':'attacker.invalid','X-Forwarded-Proto':'https'}))
    require(link['url']==target,link);require('attacker.invalid' not in link['shortUrl'],link)
    r=req('HEAD','/'+link['code'],auth=False);require(r[0]==302 and r[1].get('location')==target and not r[2],r)
    require(obj(req('GET','/api/v1/urls/'+link['code']+'/analytics'))['totalRedirects']==0,'HEAD counted')
    r=req('GET','/'+link['code'],auth=False);require(r[0]==302 and r[1].get('location')==target and r[1].get('cache-control')=='no-store',r)
    require(obj(req('GET','/api/v1/urls/'+link['code']+'/analytics'))['totalRedirects']==1,'GET not counted')
    again=create({'url':target});require(again['code']!=link['code'],'ordinary creation deduplicated')
check('destination preservation, Host independence, HEAD and GET counts',preserving)
def invalid():
    for url in ['javascript:alert(1)','file:///etc/passwd','//example.com','https://user:pass@example.com','https://example.com:0','https://example.com:65536','https://example.com:','https://example.com/a b','https://example.com/\r\nX: injected','https://example.com/%zz']:
        r=req('POST','/api/v1/urls',{'url':url});require(r[0]==400,(url,r))
    for body in ['{','{}','null','{"url":"https://example.com","unknown":true}']:
        r=req('POST','/api/v1/urls',body);require(r[0]==400,(body,r))
    r=req('POST','/api/v1/urls',{'url':'https://example.com/'+'x'*9000});require(r[0]==413,r)
check('unsafe URLs, malformed/unknown JSON and body limit',invalid)
def alias_race():
    alias='review-'+uuid.uuid4().hex[:15]
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        rs=list(pool.map(lambda _:req('POST','/api/v1/urls',{'url':'https://example.com/alias','customAlias':alias}),range(12)))
    statuses=[r[0] for r in rs];require(statuses.count(201)==1 and statuses.count(409)==11,statuses)
    alias2=alias.upper();r=create({'url':'https://example.com/case','customAlias':alias2});require(r['code']==alias2,r)
check('concurrent aliases have one winner; case is meaningful',alias_race)
def actuator_alias():
    alias='actuatorReview'+uuid.uuid4().hex[:8]
    l=create({'url':'https://example.com/prefix','customAlias':alias})
    r=req('GET','/'+l['code'],auth=False);require(r[0]==302,r)
check('actuator-prefixed alias remains a public redirect',actuator_alias)
def idempotency_race():
    key=uuid.uuid4().hex;payload={'url':'https://example.com/retry?x=1#y'}
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        rs=list(pool.map(lambda _:req('POST','/api/v1/urls',payload,extra={'Idempotency-Key':key}),range(12)))
    codes=[obj(r)['code'] for r in rs];statuses=[r[0] for r in rs]
    require(statuses.count(201)==1 and all(s in [200,201] for s in statuses) and len(set(codes))==1,(statuses,codes))
    r=req('POST','/api/v1/urls',{'url':'https://example.com/different'},extra={'Idempotency-Key':key});require(r[0]==409,r)
check('concurrent idempotency converges and mismatched replay conflicts',idempotency_race)
def counts():
    created=create({'url':'https://example.com/concurrent'})
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        rs=list(pool.map(lambda _:req('GET','/'+created['code'],auth=False),range(100)))
    require(all(r[0]==302 for r in rs),[r[0] for r in rs]);r=req('GET','/api/v1/urls/'+created['code']+'/analytics');require(obj(r)['totalRedirects']==100,r)
check('100 concurrent accepted redirects retain all increments',counts)
def expiry_disable():
    expiry=(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=2)).isoformat()
    alias='expire-'+uuid.uuid4().hex[:12];key=uuid.uuid4().hex;payload={'url':'https://example.com/expiry','customAlias':alias,'expiresAt':expiry}
    l=create(payload,extra={'Idempotency-Key':key});require(req('HEAD','/'+l['code'],auth=False)[0]==302,'not active')
    time.sleep(2.2);require(req('GET','/'+l['code'],auth=False)[0]==410,'not expired');require(obj(req('GET','/api/v1/urls/'+l['code']+'/analytics'))['totalRedirects']==0,'expired GET counted')
    require(req('POST','/api/v1/urls',{'url':'https://example.com/reuse','customAlias':alias})[0]==409,'expired alias reused')
    r=req('POST','/api/v1/urls',payload,extra={'Idempotency-Key':key});require(r[0]==200 and obj(r)['status']=='EXPIRED',r)
    path='/api/v1/urls/'+link['code'];require(req('DELETE',path,auth=False)[0]==401,'unauthenticated disable accepted')
    require(req('DELETE',path)[0]==204,'disable failed');require(req('DELETE',path)[0]==204,'repeated disable failed');require(req('GET','/'+link['code'],auth=False)[0]==410,'disabled redirect succeeds')
    require(obj(req('GET',path+'/analytics'))['totalRedirects']==1,'disabled GET counted')
check('expiry, retained alias, expired replay, protected and repeated disable',expiry_disable)
print(json.dumps({'results':results,'passed':sum(r['result']=='PASS' for r in results),'failed':sum(r['result']=='FAIL' for r in results)},indent=2))
raise SystemExit(any(r['result']=='FAIL' for r in results))
